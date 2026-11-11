"""Two real app processes, fixed dataset, open-loop reads; CI pilot, not historical JMeter results."""
import asyncio, collections, json, math, os, pathlib, statistics, subprocess, time, uuid
import aiohttp
ROOT=pathlib.Path(__file__).resolve().parents[1]
OUT=ROOT/'results'/'precheck-ci'; OUT.mkdir(parents=True,exist_ok=True)
PORTS=[28080,28081]

def save(name,obj): (OUT/name).write_text(json.dumps(obj,ensure_ascii=False,indent=2))
def stats(xs):
    xs=sorted(xs)
    return {'count':len(xs),'meanMs':round(statistics.mean(xs),2) if xs else None,'p95Ms':round(xs[max(0,math.ceil(len(xs)*.95)-1)],2) if xs else None,'maxMs':round(max(xs),2) if xs else None}

async def call(s,port,path,body=None):
    start=time.perf_counter()
    try:
        async with s.request('POST' if body is not None else 'GET',f'http://127.0.0.1:{port}'+path,json=body) as response:
            data=await response.json();return response.status,data,(time.perf_counter()-start)*1000
    except Exception as e:return 0,{'code':type(e).__name__},(time.perf_counter()-start)*1000

async def phase(s,run,duration,mixed):
    records=[]; journeys=[]; lags=[]; start=time.perf_counter()
    async def req(port,path,kind,body=None):
        status,data,elapsed=await call(s,port,path,body)
        records.append({'kind':kind,'port':port,'status':status,'code':data.get('code') if isinstance(data,dict) else None,'elapsedMs':elapsed,'at':time.perf_counter()-start})
        return status,data
    async def reads(port,kind):
        # 25 requests/s/type/server, 50 per server. No intentional dropping.
        tasks=[]
        for i in range(duration*25):
            due=start+i/25;await asyncio.sleep(max(0,due-time.perf_counter()));lags.append((time.perf_counter()-due)*1000)
            path=f'/api/{"subjects" if kind=="academic" else "offerings"}?runId={run}&department=D{i%10}&grade={i//10%4+1}'
            tasks.append(asyncio.create_task(req(port,path,kind)))
        await asyncio.gather(*tasks)
    async def journey(student):
        due=start+5+(student-1)/100;await asyncio.sleep(max(0,due-time.perf_counter()));lags.append((time.perf_counter()-due)*1000)
        begin=time.perf_counter();tried=[];outcome='ATTEMPT_LIMIT';port=PORTS[student%2]
        for attempt in range(3):
            prefix=f'/api/offerings?runId={run}&department=D0&grade=1'
            status,first=await req(port,prefix,'journeyRead')
            if status!=200:outcome='READ_FAILED';break
            status,_=await req(port,prefix+f'&afterId={max([x["id"] for x in first],default=0)}','journeyRead')
            if status!=200:outcome='READ_FAILED';break
            status,rows=await req(port,prefix+'&keyword=%EC%88%98%EA%B0%95%EC%8B%A4%EC%8A%B5','journeyRead')
            if status!=200:outcome='READ_FAILED';break
            available=sorted([x for x in rows if x['id']<=5 and x['id'] not in tried and x['enrolledCount']<x['capacity']],key=lambda x:x['id'])
            if not available:outcome='NO_AVAILABLE_COURSE';break
            course=available[0 if attempt==0 else (student-1)%len(available)]['id'];tried.append(course)
            status,data=await req(port,'/api/enrollments','submit',dict(requestId=str(uuid.uuid4()),runId=run,studentId=student,termId=202602,courseId=course))
            if status==201 and data.get('status')=='CONFIRMED':outcome='CONFIRMED';break
            if status==0 or data.get('code')=='DATABASE_RESULT_UNKNOWN':outcome='SUBMIT_UNKNOWN';break
            if status!=409 or data.get('code')!='COURSE_FULL':outcome='SUBMIT_FAILED';break
        journeys.append({'student':student,'outcome':outcome,'elapsedMs':(time.perf_counter()-begin)*1000})
    tasks=[reads(p,k) for p in PORTS for k in ['academic','offering']]
    if mixed:tasks += [journey(i) for i in range(1,501)]
    await asyncio.gather(*tasks)
    summary={'outcomes':dict(collections.Counter(x['outcome'] for x in journeys)), 'successfulJourney':stats([x['elapsedMs'] for x in journeys if x['outcome']=='CONFIRMED']), 'generatorLag':stats(lags)}
    for k in ['academic','offering','journeyRead','submit']:
        rows=[x for x in records if x['kind']==k];summary[k]=stats([x['elapsedMs'] for x in rows]);summary[k]['failed']=sum(x['status']!=200 for x in rows) if k!='submit' else sum(x['status'] not in [201,409] for x in rows)
    return dict(summary=summary,requests=records,journeys=journeys)

async def main():
    results=[]
    save('environment.json',{'revision':os.getenv('GITHUB_SHA'),'cpuCount':os.cpu_count(),'platform':os.uname().sysname,'servers':2,'mysqlLayout':'B split','poolPerDbPerServer':10,'users':500,'rampSeconds':5,'baselineSeconds':15,'mixedSeconds':45,'warmupSeconds':30,'backgroundRpsPerServer':50,'client':'aiohttp open-loop','httpTimeoutSeconds':10,'redisPersistence':'disabled for disposable hints','note':'CI pilot. Same runner shared by load generator and apps. Not comparable directly to prior 90s JMeter runs.'})
    for round_no in range(1,4):
        for enabled in ([False,True] if round_no%2 else [True,False]):
            label=f'{round_no}-{"ON" if enabled else "OFF"}';procs=[];logs=[]
            try:
                for port in PORTS:
                    f=(OUT/f'{label}-{port}.log').open('w');logs.append(f)
                    args=['java','-Xms256m','-Xmx512m','-jar',str(ROOT/'backend/target/db-split-experiment-0.0.1-SNAPSHOT.jar'),f'--server.port={port}','--experiment.mode=B','--experiment.dataset-policy=fixed','--experiment.atomic-capacity=true',f'--experiment.closed-precheck={str(enabled).lower()}','--experiment.redis.address=redis://127.0.0.1:56379','--spring.datasource.url=jdbc:mysql://127.0.0.1:53328/enrollment_experiment','--spring.datasource.username=experiment','--spring.datasource.password=experiment-local','--experiment.academic.url=jdbc:mysql://127.0.0.1:53327/enrollment_experiment','--experiment.academic.username=experiment','--experiment.academic.password=experiment-local']
                    procs.append(subprocess.Popen(args,stdout=f,stderr=subprocess.STDOUT))
                async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=10),connector=aiohttp.TCPConnector(limit=0)) as s:
                    for _ in range(90):
                        states=[await call(s,p,'/experiment/state') for p in PORTS]
                        if all(x[0]==200 for x in states):break
                        if any(p.poll() is not None for p in procs):raise RuntimeError('App startup failed')
                        await asyncio.sleep(1)
                    else:raise RuntimeError('App readiness timeout')
                    assert all(x[1]['atomicCapacity'] and x[1]['closedPrecheck']==enabled for x in states)
                    # Fixture provisioning can exceed the per-request measurement timeout.
                    async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=120)) as setup:
                        status,fixture,_=await call(setup,PORTS[0],'/experiment/fixtures',{})
                        assert status==200,fixture
                        run=fixture['runId']
                        assert (await call(setup,PORTS[0],f'/experiment/fixtures/{run}/reset',{}))[0]==200
                    await asyncio.sleep(5.1) # expire disposable hints after reset
                    await phase(s,run,30,True) # warm-up excluded
                    for p in PORTS:assert (await call(s,p,'/experiment/state'))[1]['inFlight']==0
                    assert (await call(s,PORTS[0],f'/experiment/fixtures/{run}/reset',{}))[0]==200
                    await asyncio.sleep(5.1)
                    baseline=await phase(s,run,15,False)
                    before=[(await call(s,p,'/experiment/state'))[1] for p in PORTS]
                    mixed=await phase(s,run,45,True)
                    after=[(await call(s,p,'/experiment/state'))[1] for p in PORTS]
                    status,db,_=await call(s,PORTS[0],f'/experiment/integrity?runId={run}')
                    assert status==200
                    valid=all(db[k]==0 for k in ['counterMismatches','creditViolations','scheduleViolations','studentsWithMultipleEnrollments']) and db['confirmed']==mixed['summary']['outcomes'].get('CONFIRMED',0) and db['totalCatalogRows']==10000
                    result={'label':label,'enabled':enabled,'round':round_no,'integrityValid':valid,'baseline':baseline['summary'],'mixed':mixed['summary'],'database':db,'before':before,'after':after}
                    save(label+'-raw.json',{'baseline':baseline,'mixed':mixed});save(label+'.json',result);results.append(result);save('results.json',results)
                    print(json.dumps(result,ensure_ascii=False),flush=True)
                    if not valid:raise RuntimeError('Integrity failure; preserved evidence')
            finally:
                for p in procs:
                    if p.poll() is None:p.terminate()
                for p in procs:
                    try:p.wait(timeout=15)
                    except subprocess.TimeoutExpired:p.kill();p.wait()
                for f in logs:f.close()
            await asyncio.sleep(3)
asyncio.run(main())
