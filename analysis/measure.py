"""Disposable CI only. Original service/Facade SQL and transaction boundaries are retained."""
import concurrent.futures, csv, json, math, os, platform, signal, statistics
import subprocess, threading, time, urllib.request, urllib.error
from pathlib import Path
import pymysql

ROOT=Path.cwd(); OUT=ROOT/'evidence'; OUT.mkdir(exist_ok=True)
RAMP=float(os.environ.get('ANALYSIS_RAMP_SECONDS','5'))
ROUNDS=int(os.environ.get('ANALYSIS_ROUNDS','3'))
DB=dict(host='127.0.0.1', user='root', password='analysis-test-password', database='atomic_analysis', autocommit=True)
def connect(): return pymysql.connect(**DB, cursorclass=pymysql.cursors.DictCursor)
def execute(db, sql, args=None):
    with db.cursor() as c: c.execute(sql,args); return c.fetchall()
def reset(db):
    assert execute(db,'SELECT DATABASE() AS db')[0]['db']=='atomic_analysis'
    execute(db,'DELETE FROM enrollments')
    execute(db,'UPDATE courses SET capacity=100,enrolled_count=0 WHERE id=1')
def seed(db):
    for i in range(500):
        execute(db,"INSERT INTO students(id,student_number,name,status,max_credits) VALUES(%s,%s,%s,'ACTIVE',18) ON DUPLICATE KEY UPDATE status='ACTIVE',max_credits=18",(10000+i,'ANA'+str(i),'Analysis '+str(i)))
def post(i):
    request=urllib.request.Request(f'http://127.0.0.1:{8080+i%2}/api/enrollments',
        data=json.dumps(dict(studentId=10000+i,courseId=1)).encode(),headers={'Content-Type':'application/json'},method='POST')
    begin=time.monotonic(); epoch=time.time()*1000
    try:
        with urllib.request.urlopen(request,timeout=90) as r: code=r.status; body=r.read().decode()
    except urllib.error.HTTPError as e: code=e.code; body=e.read().decode()
    except Exception as e: code='CLIENT_ERROR'; body=str(e)
    return dict(studentId=10000+i,port=8080+i%2,startMs=round(epoch,3),elapsedMs=round((time.monotonic()-begin)*1000,3),code=str(code),body=body)
def start_apps(variant,pool,folder):
    procs=[]
    env=os.environ.copy(); env.update(DB_URL='jdbc:mysql://localhost:3306/atomic_analysis?serverTimezone=UTC&characterEncoding=UTF-8',DB_USERNAME='root',DB_PASSWORD=DB['password'],REDIS_ADDRESS='redis://localhost:6379')
    try:
        for port in (8080,8081):
            dest=folder/str(port); dest.mkdir(parents=True)
            log=open(dest/'server.log','w')
            args=['java','-Xms256m','-Xmx512m','-jar',str(ROOT/variant/'backend/target/class-enrollment-backend-0.0.1-SNAPSHOT.jar'),f'--server.port={port}',f'--spring.datasource.hikari.maximum-pool-size={pool}',f'--spring.datasource.hikari.minimum-idle={pool}', '--logging.level.root=WARN','--analysis.enabled=true',f'--analysis.folder={dest}']
            p=subprocess.Popen(args,env=env,stdout=log,stderr=subprocess.STDOUT); procs.append((p,log))
        for port in (8080,8081):
            deadline=time.monotonic()+100
            while time.monotonic()<deadline:
                if any(p.poll() is not None for p,_ in procs): raise RuntimeError(f'JVM stopped; inspect {folder}')
                try:
                    with urllib.request.urlopen(f'http://127.0.0.1:{port}/api/health',timeout=1) as r:
                        if r.status==200: break
                except urllib.error.HTTPError as e:
                    # Existing project's health endpoint is /health; try it below.
                    pass
                except Exception: pass
                try:
                    with urllib.request.urlopen(f'http://127.0.0.1:{port}/health',timeout=1) as r:
                        if r.status==200: break
                except Exception: pass
                time.sleep(.3)
            else: raise RuntimeError(f'port {port} did not start')
        return procs
    except BaseException:
        stop_apps(procs); raise
def stop_apps(procs):
    for p,_ in procs:
        if p.poll() is None: p.terminate()
    for p,log in procs:
        try: p.wait(timeout=20)
        except subprocess.TimeoutExpired: p.kill(); p.wait()
        log.close()
def monitor(stop,folder,samples):
    db=connect()
    try:
        while not stop.is_set():
            rows=execute(db,"""SELECT w.REQUESTING_ENGINE_TRANSACTION_ID AS waiting_tx,
                w.BLOCKING_ENGINE_TRANSACTION_ID AS blocking_tx,
                l.OBJECT_NAME AS object_name,l.INDEX_NAME AS index_name,l.LOCK_MODE AS lock_mode,l.LOCK_DATA AS lock_data
                FROM performance_schema.data_lock_waits w
                JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                WHERE l.OBJECT_SCHEMA='atomic_analysis'""")
            samples.append(dict(epochMs=time.time()*1000,waitingTransactions=len({r['waiting_tx'] for r in rows}),waitEdges=len(rows)))
            if rows and not (folder/'first-lock-waits.json').exists():
                (folder/'first-lock-waits.json').write_text(json.dumps(rows,indent=2,default=str))
            stop.wait(.05)
    finally: db.close()
def p95(values): return sorted(values)[math.ceil(.95*len(values))-1] if values else 0
def stat(values): return dict(count=len(values),avgMs=round(statistics.mean(values),3) if values else 0,p95Ms=round(p95(values),3),maxMs=round(max(values),3) if values else 0)
def read(path):
    with path.open() as f:return list(csv.DictReader(f))
db=connect()
environment=dict(cpuCount=os.cpu_count(),platform=platform.platform(),java=subprocess.check_output(['java','-version'],stderr=subprocess.STDOUT,text=True),baselineCommit='6ebba4d65808e947bc67a0e39afacfec66e51443',atomicBusinessCommit='d2c9aec979ec1aca7e23b4e935dcd2a0edd32007',requests=500,rampSeconds=RAMP,rounds=ROUNDS,jvms=2,logging='root WARN identical',client='Python urllib 500 workers',observer='JDBC proxy in both variants; pool and MySQL wait sampling every 50ms')
environment['mysql']=execute(db,'SELECT VERSION() AS version,@@transaction_isolation AS isolation_level,@@innodb_flush_log_at_trx_commit AS flush_log,@@sync_binlog AS sync_binlog')
(OUT/'environment.json').write_text(json.dumps(environment,indent=2))
summary=[]
for round_no in range(1,ROUNDS+1):
    cases=[('redisson',10),('atomic',10),('atomic',50)]
    if round_no==2: cases.reverse()
    for variant,pool in cases:
        folder=OUT/f'{variant}-pool{pool}-r{round_no}';folder.mkdir()
        procs=start_apps(variant,pool,folder)
        samples=[]; stop=threading.Event(); watcher=None
        try:
            seed(db);reset(db)
            # Same 20 sequential successful warmups for each newly started JVM pair.
            for i in range(20):
                assert post(i)['code']=='201'
            reset(db)
            begin_epoch=time.time()*1000
            watcher=threading.Thread(target=monitor,args=(stop,folder,samples));watcher.start()
            release=threading.Event(); origin=[0.0]
            def send(i):
                release.wait(); delay=origin[0]+i*(RAMP/500)-time.monotonic()
                if delay>0:time.sleep(delay)
                return post(i)
            with concurrent.futures.ThreadPoolExecutor(max_workers=500) as executor:
                futures=[executor.submit(send,i) for i in range(500)]
                origin[0]=time.monotonic(); release.set()
                results=[f.result(timeout=100) for f in futures]
            end_epoch=time.time()*1000
            with (folder/'http.csv').open('w') as f:
                writer=csv.DictWriter(f,fieldnames=list(results[0]));writer.writeheader();writer.writerows(results)
            final=execute(db,'SELECT capacity,enrolled_count,(SELECT COUNT(*) FROM enrollments WHERE course_id=1) AS enrollment_count FROM courses WHERE id=1')[0]
            (folder/'final-state.json').write_text(json.dumps(final,indent=2))
            counts={code:sum(r['code']==code for r in results) for code in sorted({r['code'] for r in results})}
            assert counts.get('201',0)==100 and final['enrolled_count']==100 and final['enrollment_count']==100,(counts,final)
            assert set(counts)<= {'201','409','503'},counts
        finally:
            stop.set()
            if watcher:watcher.join(timeout=10)
            stop_apps(procs)
            (folder/'mysql-samples.json').write_text(json.dumps(samples,indent=2))
        events=[];pools=[]
        for port in (8080,8081):
            events.extend(r for r in read(folder/str(port)/'jdbc.csv') if begin_epoch<=float(r['epochMs'])<=end_epoch)
            pools.extend(r for r in read(folder/str(port)/'pool.csv') if begin_epoch<=float(r['epochMs'])<=end_epoch)
        phases={}
        for r in events:
            key=r['sqlKind'] if r['phase']=='sql' else r['phase']
            phases.setdefault(key,[]).append(float(r['durationMs']))
        report=dict(variant=variant,pool=pool,round=round_no,rampSeconds=RAMP,http=stat([r['elapsedMs'] for r in results]),codes=counts,byCode={code:stat([r['elapsedMs'] for r in results if r['code']==code]) for code in counts},jdbc={k:stat(v) for k,v in phases.items()},maxPoolPendingPerJvm=max([int(r['pending']) for r in pools],default=0),maxPoolActivePerJvm=max([int(r['active']) for r in pools],default=0),maxMySqlWaitingTransactions=max([r['waitingTransactions'] for r in samples],default=0),mysqlSamplesWithWaits=sum(r['waitingTransactions']>0 for r in samples),finalState=final)
        summary.append(report)
        (OUT/'summary.json').write_text(json.dumps(summary,indent=2))
        print(json.dumps(report),flush=True)
db.close()
