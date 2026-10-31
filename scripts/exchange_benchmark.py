"""12,000 distinct exchange applications across two JVMs; disposable DB only.
Unlike the baseline benchmark, 202 is pending, never counted as enrollment success.
"""
import asyncio, collections, csv, json, pathlib, subprocess, time
import aiohttp
import pymysql
import requests

N=12000
OUT=pathlib.Path('results/exchange');OUT.mkdir(parents=True,exist_ok=True)

def db():
    return pymysql.connect(host='127.0.0.1',user='class_user',password='class_password',database='class_enrollment',autocommit=True,cursorclass=pymysql.cursors.DictCursor)

def seed():
    with db() as con,con.cursor() as c:
        for table in ('exchange_requests','enrollment_requests','enrollments','course_schedules','courses','students'): c.execute('DELETE FROM '+table)
        c.executemany("INSERT INTO students(id,student_number,name,status,max_credits) VALUES (%s,%s,'exchange','ACTIVE',18)",[(i+1,f'E{i+1}') for i in range(N)])
        c.executemany("INSERT INTO courses(id,course_code,name,professor,department,academic_year,semester,credits,capacity) VALUES (%s,%s,'exchange','p','cs',2026,2,3,120)",[(i+1000,f'E{i}') for i in range(50)])

def ready(port,process):
    for _ in range(120):
        if process.poll() is not None: raise RuntimeError('JVM exited')
        try:
            if requests.get(f'http://localhost:{port}/actuator/health',timeout=1).status_code==200:return
        except requests.RequestException:pass
        time.sleep(1)
    raise RuntimeError('startup timeout')

async def submit(indices, prefix):
    semaphore=asyncio.Semaphore(128)
    async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=20),connector=aiohttp.TCPConnector(limit=128)) as session:
        async def send(i):
            async with semaphore:
                start=time.perf_counter()
                async with session.post(f'http://localhost:{8080+i%2}/api/exchange-enrollments',json={'studentId':i+1,'courseId':1000+i%50},headers={'Idempotency-Key':f'{prefix}-{i}'}) as r:
                    data=await r.json()
                    assert r.status in (200,202,409),(r.status,data)
                    return {'index':i,'http':r.status,'ms':(time.perf_counter()-start)*1000,**data}
        return await asyncio.gather(*(send(i) for i in indices))

async def verify_partner(rows):
    semaphore=asyncio.Semaphore(128)
    async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=20),connector=aiohttp.TCPConnector(limit=128)) as session:
        async def check(row):
            async with semaphore:
                async with session.get(f"http://localhost:8090/reservations/{row['id']}",params={'student':row['student_id'],'course':row['course_id']}) as r:
                    state=r.headers.get('Reservation-State')
                    expected=('CONFIRMED',) if row['phase']=='COMPLETED' else ('CANCELLED','FULL')
                    assert r.status==200 and state in expected,(row,state)
                    return state
        return collections.Counter(await asyncio.gather(*(check(row) for row in rows)))

processes=[];logs=[]
try:
    jar='backend/target/class-enrollment-backend-0.0.1-SNAPSHOT.jar'
    for port in (8080,8081):
        log=open(OUT/f'{port}.log','w');logs.append(log)
        process=subprocess.Popen(['java','-Xms256m','-Xmx512m','-jar',jar,f'--server.port={port}'],stdout=log,stderr=subprocess.STDOUT)
        processes.append(process);ready(port,process)
    seed()
    prefix='exchange-'+str(time.time_ns())
    start=time.perf_counter();responses=asyncio.run(submit(range(N),prefix));elapsed=time.perf_counter()-start
    (OUT/'responses.json').write_text(json.dumps(responses))
    until=time.monotonic()+300
    while True:
        with db() as con,con.cursor() as c:
            c.execute("SELECT * FROM exchange_requests");rows=c.fetchall()
        pending=[r for r in rows if r['phase'] not in ('COMPLETED','CANCELLED')]
        if not pending:break
        if time.monotonic()>until:raise AssertionError(f'{len(pending)} unresolved requests')
        # Replay the original keys, simulating callers polling/retrying without making new reservations.
        asyncio.run(submit([r['student_id']-1 for r in pending],prefix))
        time.sleep(2)
    states=collections.Counter(r['phase'] for r in rows)
    assert len(rows)==N and states=={'COMPLETED':6000,'CANCELLED':6000},states
    with db() as con,con.cursor() as c:
        c.execute('SELECT COUNT(*) n FROM enrollments');assert c.fetchone()['n']==6000
        c.execute('SELECT COUNT(*) n FROM courses c WHERE enrolled_count>capacity OR enrolled_count<>(SELECT COUNT(*) FROM enrollments e WHERE e.course_id=c.id)');assert c.fetchone()['n']==0
        c.execute("SELECT COUNT(*) n FROM exchange_requests x LEFT JOIN enrollments e ON e.id=x.enrollment_id WHERE x.phase='COMPLETED' AND (e.id IS NULL OR e.student_id<>x.student_id OR e.course_id<>x.course_id)");assert c.fetchone()['n']==0
        c.execute("SELECT COUNT(*) n FROM exchange_requests WHERE phase='CANCELLED' AND enrollment_id IS NOT NULL");assert c.fetchone()['n']==0
    partner=asyncio.run(verify_partner(rows))
    replay=asyncio.run(submit(range(100),prefix))
    original={r['index']:r['requestId'] for r in responses}
    assert all(original[r['index']]==r['requestId'] and r['phase'] in ('COMPLETED','CANCELLED') for r in replay)
    result={'unique_requests':N,'concurrency':128,'jvms':2,'submission_seconds':elapsed,'initial_http':dict(collections.Counter(r['http'] for r in responses)),'terminal_states':dict(states),'partner_states':dict(partner),'replays_checked':100,'invariants':'PASS','scenario':'partner university durable seat reservation','note':'Not comparable to read-only eligibility latency; 202 is pending.'}
    (OUT/'summary.json').write_text(json.dumps(result,indent=2));print(json.dumps(result),flush=True)
finally:
    for process in processes:process.terminate()
    for process in processes:
        try:process.wait(timeout=15)
        except subprocess.TimeoutExpired:process.kill();process.wait()
    for log in logs:log.close()
