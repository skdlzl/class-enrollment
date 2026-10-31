"""Three strategies x two workloads. DELETES disposable local DB data per trial.
Remote course IDs are unique per trial; partner history is preserved. Run in a fresh CI volume.
"""
import argparse, asyncio, collections, json, math, os, pathlib, platform, subprocess, time
import aiohttp
import pymysql
import requests

p=argparse.ArgumentParser()
p.add_argument('--round-offset',type=int,default=0)
p.add_argument('--requests',type=int,default=12000)
p.add_argument('--concurrency',type=int,default=128)
a=p.parse_args();assert a.requests>=10000
ROOT=pathlib.Path('results/exchange-compare');ROOT.mkdir(parents=True,exist_ok=True)
N=a.requests

def db():
    return pymysql.connect(host='127.0.0.1',user='class_user',password='class_password',database='class_enrollment',autocommit=True,cursorclass=pymysql.cursors.DictCursor)

def seed(base,courses):
    with db() as con,con.cursor() as c:
        for table in ('exchange_requests','enrollment_requests','enrollments','course_schedules','courses','students'):c.execute('DELETE FROM '+table)
        c.executemany("INSERT INTO students(id,student_number,name,status,max_credits) VALUES (%s,%s,'exchange','ACTIVE',18)",[(i+1,f'E{i+1}') for i in range(N)])
        c.executemany("INSERT INTO courses(id,course_code,name,professor,department,academic_year,semester,credits,capacity) VALUES (%s,%s,'exchange','p','cs',2026,2,3,120)",[(base+i,f'E{i}') for i in range(courses)])

def ready(port,process):
    for _ in range(120):
        if process.poll() is not None:raise RuntimeError('JVM exited')
        try:
            if requests.get(f'http://localhost:{port}/actuator/health',timeout=1).status_code==200:return
        except requests.RequestException:pass
        time.sleep(1)
    raise RuntimeError('startup timeout')

async def submit(indices,prefix,base,courses):
    sem=asyncio.Semaphore(a.concurrency)
    async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=20),connector=aiohttp.TCPConnector(limit=a.concurrency)) as session:
        async def send(i):
            async with sem:
                epoch=time.time();start=time.perf_counter()
                try:
                    async with session.post(f'http://localhost:{8080+i%2}/api/exchange-enrollments',json={'studentId':i+1,'courseId':base+i%courses},headers={'Idempotency-Key':f'{prefix}-{i}'}) as r:
                        data=await r.json()
                        return {'index':i,'http':r.status,'started_epoch':epoch,'ms':(time.perf_counter()-start)*1000,**data}
                except (aiohttp.ClientError,asyncio.TimeoutError) as e:
                    return {'index':i,'http':0,'started_epoch':epoch,'ms':(time.perf_counter()-start)*1000,'code':type(e).__name__}
        return await asyncio.gather(*(send(i) for i in indices))

async def verify_partner(rows):
    sem=asyncio.Semaphore(128)
    async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=20),connector=aiohttp.TCPConnector(limit=128)) as session:
        async def check(row):
            async with sem:
                async with session.get(f"http://localhost:8090/reservations/{row['id']}",params={'student':row['student_id'],'course':row['course_id']}) as r:
                    state=r.headers.get('Reservation-State')
                    expected=('CONFIRMED',) if row['phase']=='COMPLETED' else ('CANCELLED','FULL')
                    assert r.status==200 and state in expected,(row,state)
                    return state
        return collections.Counter(await asyncio.gather(*(check(r) for r in rows)))

def metrics():
    result={}
    for port in (8080,8081):
        root=f'http://localhost:{port}/actuator/metrics'
        names=requests.get(root,timeout=10).json()['names'];values={}
        for name in names:
            if not name.startswith(('exchange.','hikaricp.','enrollment.stage')):continue
            obj=requests.get(root+'/'+name,timeout=10).json();values[name]=obj
            tag={'exchange.retries':'cause','exchange.partner':'action','enrollment.stage':'stage'}.get(name)
            if tag:
                for available in obj.get('availableTags',[]):
                    if available['tag']==tag:
                        for value in available['values']:
                            values[name+':'+value]=requests.get(root+'/'+name,params={'tag':tag+':'+value},timeout=10).json()
        result[str(port)]=values
    return result

def percentile(values,q):
    return sorted(values)[max(0,math.ceil(len(values)*q)-1)] if values else None

summaries=[]
strategies=['PESSIMISTIC','OPTIMISTIC','REDIS']
strategies=strategies[a.round_offset%3:]+strategies[:a.round_offset%3]
for si,strategy in enumerate(strategies):
    for wi,(workload,courses) in enumerate([('distributed',50),('hot',1)]):
        name=f'{a.round_offset+1}-{strategy}-{workload}'
        out=ROOT/name;out.mkdir(parents=True,exist_ok=True)
        # Unique course IDs also isolate the partner's durable state without a reset endpoint.
        base=10000+a.round_offset*10000+si*1000+wi*100
        procs=[];logs=[]
        try:
            # Seed while no app/recovery worker is running. First boot performs migration first.
            for port in (8080,8081):
                log=open(out/f'{port}.log','w');logs.append(log)
                proc=subprocess.Popen(['java','-Xms256m','-Xmx512m','-jar','backend/target/class-enrollment-backend-0.0.1-SNAPSHOT.jar',f'--server.port={port}',f'--enrollment.strategy={strategy}'],stdout=log,stderr=subprocess.STDOUT)
                procs.append(proc);ready(port,proc)
            seed(base,courses)
            # No warm-up: JVM cold/JIT cost is included equally and order rotates across rounds.
            prefix=name;before=metrics();start=time.perf_counter()
            responses=asyncio.run(submit(range(N),prefix,base,courses));initial_seconds=time.perf_counter()-start
            (out/'responses.json').write_text(json.dumps(responses))
            retries=[];until=time.monotonic()+600
            while True:
                with db() as con,con.cursor() as c:
                    c.execute("SELECT id,student_id,course_id,phase,enrollment_id,last_error,attempts,UNIX_TIMESTAMP(updated_at) terminal_epoch FROM exchange_requests");rows=c.fetchall()
                settled={r['student_id']-1 for r in rows if r['phase'] in ('COMPLETED','CANCELLED')}
                if len(settled)==N:break
                assert time.monotonic()<until,('unresolved',N-len(settled))
                retry=asyncio.run(submit([i for i in range(N) if i not in settled],prefix,base,courses));retries.extend(retry)
                time.sleep(2)
            total_seconds=time.perf_counter()-start
            after=metrics() # Snapshot BEFORE verification traffic.
            (out/'metrics.json').write_text(json.dumps({'before':before,'after':after}))
            (out/'retries.json').write_text(json.dumps(retries))
            (out/'terminal.json').write_text(json.dumps(rows,default=str))
            states=collections.Counter(r['phase'] for r in rows);capacity=courses*120
            assert len(rows)==N and states=={'COMPLETED':capacity,'CANCELLED':N-capacity},states
            with db() as con,con.cursor() as c:
                c.execute('SELECT COUNT(*) n FROM enrollments');assert c.fetchone()['n']==capacity
                c.execute('SELECT COUNT(*) n FROM courses c WHERE enrolled_count>capacity OR enrolled_count<>(SELECT COUNT(*) FROM enrollments e WHERE e.course_id=c.id)');assert c.fetchone()['n']==0
                c.execute("SELECT COUNT(*) n FROM exchange_requests x LEFT JOIN enrollments e ON e.id=x.enrollment_id WHERE x.phase='COMPLETED' AND (e.id IS NULL OR e.student_id<>x.student_id OR e.course_id<>x.course_id)");assert c.fetchone()['n']==0
                c.execute("SELECT COUNT(*) n FROM exchange_requests WHERE phase='CANCELLED' AND enrollment_id IS NOT NULL");assert c.fetchone()['n']==0
                c.execute('SELECT COUNT(*) n FROM enrollment_requests WHERE enrollment_id IS NOT NULL');assert c.fetchone()['n']==capacity
            partner=asyncio.run(verify_partner(rows))
            replay=asyncio.run(submit(range(100),prefix,base,courses));by_student={r['student_id']:r for r in rows}
            assert all(r.get('requestId')==by_student[r['index']+1]['id'] and r.get('phase')==by_student[r['index']+1]['phase'] for r in replay)
            starts={r['index']+1:r['started_epoch'] for r in responses}
            completed_ms=[max(0,(float(r['terminal_epoch'])-starts[r['student_id']])*1000) for r in rows if r['phase']=='COMPLETED']
            result={'name':name,'strategy':strategy,'workload':workload,'unique_requests':N,'concurrency':a.concurrency,'jvms':2,'initial_seconds':initial_seconds,'settlement_seconds':total_seconds,'settled_rps':N/total_seconds,'initial_http':dict(collections.Counter(str(r['http']) for r in responses)),'initial_pending_causes':dict(collections.Counter(r.get('lastError') or 'CLAIMED' for r in responses if r['http']==202)),'terminal_states':dict(states),'partner_states':dict(partner),'client_retries':len(retries),'attempts':sum(r['attempts'] for r in rows),'completed_p95_ms':percentile(completed_ms,.95),'completed_p99_ms':percentile(completed_ms,.99),'invariants':'PASS'}
            summaries.append(result);(out/'summary.json').write_text(json.dumps(result,indent=2))
            (ROOT/'summary.json').write_text(json.dumps({'environment':platform.platform(),'cpu_count':os.cpu_count(),'results':summaries},indent=2))
            print(json.dumps(result),flush=True)
        finally:
            for proc in procs:proc.terminate()
            for proc in procs:
                try:proc.wait(timeout=15)
                except subprocess.TimeoutExpired:proc.kill();proc.wait()
            for log in logs:log.close()
