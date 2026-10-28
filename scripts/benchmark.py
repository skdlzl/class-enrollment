"""Real HTTP load against two JVMs. Run only against a disposable benchmark database."""
import argparse, collections, concurrent.futures, csv, json, os, pathlib, platform, statistics, subprocess, threading, time
import pymysql
import requests

p=argparse.ArgumentParser()
p.add_argument('--requests',type=int,default=12000)
p.add_argument('--concurrency',type=int,default=128)
p.add_argument('--rounds',type=int,default=3)
p.add_argument('--output',default='results')
a=p.parse_args()
assert a.requests >= 10000
out=pathlib.Path(a.output); out.mkdir(parents=True,exist_ok=True)
jar=pathlib.Path('backend/target/class-enrollment-backend-0.0.1-SNAPSHOT.jar').resolve()

def connect():
    return pymysql.connect(host='127.0.0.1',user='class_user',password='class_password',database='class_enrollment',autocommit=True)

def seed(courses,capacity):
    with connect() as db, db.cursor() as c:
        c.execute('DELETE FROM enrollment_requests'); c.execute('DELETE FROM enrollments')
        c.execute('DELETE FROM course_schedules'); c.execute('DELETE FROM courses'); c.execute('DELETE FROM students')
        c.executemany("INSERT INTO students(id,student_number,name,status,max_credits) VALUES (%s,%s,'load','ACTIVE',18)",[(i+1,f'L{i+1}') for i in range(a.requests)])
        c.executemany("INSERT INTO courses(id,course_code,name,professor,department,academic_year,semester,credits,capacity) VALUES (%s,%s,'load','p','cs',2026,2,3,%s)",[(i+1000,f'C{i}',capacity) for i in range(courses)])
        c.executemany("INSERT INTO course_schedules(course_id,day_of_week,start_time,end_time) VALUES (%s,'MONDAY','09:00','10:00')",[(i+1000,) for i in range(courses)])

def ready(port,proc):
    for _ in range(120):
        if proc.poll() is not None: raise RuntimeError(f'JVM {port} exited')
        try:
            if requests.get(f'http://localhost:{port}/actuator/health',timeout=1).status_code==200:return
        except requests.RequestException: pass
        time.sleep(1)
    raise RuntimeError('startup timeout')

local=threading.local()
def request(i,courses,prefix):
    if not hasattr(local,'session'):local.session=requests.Session()
    start=time.perf_counter()
    try:
        r=local.session.post(f'http://localhost:{8080+i%2}/api/enrollments',json={'studentId':i+1,'courseId':1000+i%courses},headers={'Idempotency-Key':f'{prefix}-{i}'},timeout=15)
        data=r.json()
        code=data.get('code','CREATED' if r.status_code==201 else 'REPLAY' if r.status_code==200 else 'UNEXPECTED')
        return i,r.status_code,code,(time.perf_counter()-start)*1000
    except requests.RequestException as e:return i,0,type(e).__name__,(time.perf_counter()-start)*1000

def percentile(values,q):
    values=sorted(values)
    return values[max(0,__import__('math').ceil(len(values)*q)-1)] if values else None

def metrics():
    result={}
    for port in (8080,8081):
        names=requests.get(f'http://localhost:{port}/actuator/metrics',timeout=5).json()['names']
        result[str(port)]={name:requests.get(f'http://localhost:{port}/actuator/metrics/{name}',timeout=5).json() for name in names if name.startswith(('enrollment.','hikaricp.'))}
        # Preserve stage and outcome breakdowns, not only aggregated timer totals.
        for name,tag in [('enrollment.stage','stage'),('enrollment.requests','outcome'),('enrollment.retries','cause')]:
            base=result[str(port)].get(name,{})
            for available in base.get('availableTags',[]):
                if available['tag']==tag:
                    for value in available['values']:
                        result[str(port)][f'{name}:{value}']=requests.get(f'http://localhost:{port}/actuator/metrics/{name}',params={'tag':f'{tag}:{value}'},timeout=5).json()
    return result

summaries=[]
for round_ in range(a.rounds):
    strategies=['PESSIMISTIC','OPTIMISTIC','REDIS']
    strategies=strategies[round_%3:]+strategies[:round_%3]
    for strategy in strategies:
        for workload,courses,capacity in [('distributed',50,120),('hot',1,100)]:
            name=f'{round_+1}-{strategy}-{workload}'
            procs=[]; logs=[]
            try:
                for port in (8080,8081):
                    log=open(out/f'{name}-{port}.log','w');logs.append(log)
                    proc=subprocess.Popen(['java','-Xms256m','-Xmx512m','-jar',str(jar),f'--server.port={port}',f'--enrollment.strategy={strategy}'],stdout=log,stderr=subprocess.STDOUT)
                    procs.append(proc);ready(port,proc)
                seed(courses,capacity)
                # Warm-up uses a separate seed; measurements exclude startup and warm-up.
                for i in range(100):request(i,courses,'warmup')
                seed(courses,capacity)
                before=metrics()
                start=time.perf_counter()
                with concurrent.futures.ThreadPoolExecutor(a.concurrency) as pool:
                    rows=list(pool.map(lambda i:request(i,courses,name),range(a.requests)))
                elapsed=time.perf_counter()-start
                with open(out/f'{name}.csv','w') as f:
                    w=csv.writer(f);w.writerow(['request','http_status','outcome','milliseconds']);w.writerows(rows)
                with connect() as db,db.cursor() as c:
                    c.execute('SELECT COUNT(*) FROM courses c WHERE enrolled_count<>(SELECT COUNT(*) FROM enrollments e WHERE e.course_id=c.id) OR enrolled_count>capacity'); assert c.fetchone()[0]==0
                    c.execute('SELECT COUNT(*) FROM enrollments'); saved=c.fetchone()[0]
                    c.execute('SELECT COUNT(*) FROM enrollment_requests WHERE enrollment_id IS NOT NULL'); assert c.fetchone()[0]==saved
                    c.execute('SELECT COUNT(*) FROM (SELECT student_id,course_id FROM enrollments GROUP BY student_id,course_id HAVING COUNT(*)>1) d');assert c.fetchone()[0]==0
                assert saved==sum(r[1]==201 for r in rows),(saved,collections.Counter(r[2] for r in rows))
                assert all(r[1] in (201,409,503) for r in rows),collections.Counter(r[2] for r in rows)
                summary=dict(name=name,strategy=strategy,workload=workload,requests=a.requests,concurrency=a.concurrency,seconds=elapsed,rps=a.requests/elapsed,created=saved,counts=dict(collections.Counter(r[2] for r in rows)),latency={str(status):{'p50_ms':percentile([r[3] for r in rows if r[1]==status],.5),'p95_ms':percentile([r[3] for r in rows if r[1]==status],.95),'p99_ms':percentile([r[3] for r in rows if r[1]==status],.99)} for status in (201,409,503)},invariants='PASS')
                summaries.append(summary)
                (out/f'{name}-metrics.json').write_text(json.dumps({'before':before,'after':metrics()},indent=2))
                (out/'summary.json').write_text(json.dumps({'environment':platform.platform(),'cpu_count':os.cpu_count(),'external_delay_ms':os.getenv('EXTERNAL_DELAY_MS','20'),'results':summaries},indent=2))
                print(json.dumps(summary),flush=True)
            finally:
                for proc in procs:proc.terminate()
                for proc in procs:
                    try:proc.wait(timeout=15)
                    except subprocess.TimeoutExpired:proc.kill();proc.wait()
                for log in logs:log.close()
