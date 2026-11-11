"""Controlled cached-selection scenarios. Separate from the original fresh-search journey."""
import asyncio, collections, json, os, pathlib, subprocess, sys, time, uuid
import aiohttp
from importlib.util import spec_from_file_location, module_from_spec
ROOT=pathlib.Path(__file__).resolve().parents[1]
spec=spec_from_file_location('base',ROOT/'scripts/measure-precheck.py'); base=module_from_spec(spec);spec.loader.exec_module(base)
CASE=sys.argv[1]
assert CASE in ['hot-5s','delayed-click','burst-100ms']
OUT=ROOT/'results'/('precheck-'+CASE);OUT.mkdir(parents=True,exist_ok=True)
PORTS=[28080,28081];N=1000
COMPOSE=['docker','compose','-p','precheck-ci','-f',str(ROOT/'compose.compare.yml')]
def save(name,obj): (OUT/name).write_text(json.dumps(obj,ensure_ascii=False,indent=2))
def offset(i):
    if CASE=='hot-5s':return i*5/N
    if CASE=='burst-100ms':return i*.1/N
    return 1+((i*37)%N)*2/(N-1) # deterministic 1..3 seconds from common release, same ON/OFF
async def reset(s,run):
    for p in PORTS:assert (await base.call(s,p,'/experiment/state'))[1]['inFlight']==0
    assert (await base.call(s,PORTS[0],f'/experiment/fixtures/{run}/reset',{}))[0]==200
    # Dedicated disposable Redis holds hints only. No shared Redis or production data.
    subprocess.run(COMPOSE+['exec','-T','redis','redis-cli','FLUSHDB'],check=True,capture_output=True)
async def snapshots(s,run):
    sem=asyncio.Semaphore(50)
    async def one(i):
        async with sem:
            status,rows,ms=await base.call(s,PORTS[i%2],f'/api/offerings?runId={run}&department=D0&grade=1')
            assert status==200,rows
            target=next(x for x in rows if x['id']==1)
            assert target['capacity']==100 and target['enrolledCount']==0,target
            return {'student':i+1,'courseId':target['id'],'snapshot':target,'readMs':ms,'readAt':time.perf_counter()}
    return await asyncio.gather(*(one(i) for i in range(N)))
async def background(s,run,start,duration,rows,lags):
    async def stream(port,kind):
        pending=[]
        async def request(i):
            status,data,ms=await base.call(s,port,f'/api/{"subjects" if kind=="academic" else "offerings"}?runId={run}&department=D{i%10}&grade={i//10%4+1}')
            rows.append({'kind':kind,'status':status,'elapsedMs':ms,'port':port,'at':time.perf_counter()-start})
        for i in range(duration*25):
            due=start+i/25;await asyncio.sleep(max(0,due-time.perf_counter()));lags.append((time.perf_counter()-due)*1000)
            pending.append(asyncio.create_task(request(i)))
        await asyncio.gather(*pending)
    await asyncio.gather(*(stream(p,k) for p in PORTS for k in ['academic','offering']))
async def measure(s,run,selections,duration=15):
    rows=[];lags=[];start=time.perf_counter();requests=[]
    async def submit(i,selected):
        due=start+2+offset(i);await asyncio.sleep(max(0,due-time.perf_counter()));lag=(time.perf_counter()-due)*1000;lags.append(lag)
        rid=str(uuid.uuid4());sent=time.perf_counter()
        status,data,ms=await base.call(s,PORTS[i%2],'/api/enrollments',dict(requestId=rid,runId=run,studentId=selected['student'],termId=202602,courseId=selected['courseId']))
        outcome='CONFIRMED' if status==201 and data.get('status')=='CONFIRMED' else data.get('code','UNEXPECTED')
        requests.append({'requestId':rid,'student':selected['student'],'status':status,'outcome':outcome,'elapsedMs':ms,'scheduledAt':due-start,'startedAt':sent-start,'lagMs':lag,'snapshotAgeMs':(sent-selected['readAt'])*1000})
    await asyncio.gather(background(s,run,start,duration,rows,lags),*(submit(i,x) for i,x in enumerate(selections)))
    summary={'outcomes':dict(collections.Counter(x['outcome'] for x in requests)),'submit':base.stats([x['elapsedMs'] for x in requests]),'confirmedApi':base.stats([x['elapsedMs'] for x in requests if x['outcome']=='CONFIRMED']),'fullApi':base.stats([x['elapsedMs'] for x in requests if x['outcome']=='COURSE_FULL']),'generatorLag':base.stats(lags),'submitLag':base.stats([x['lagMs'] for x in requests])}
    for kind in ['academic','offering']:
        subset=[x for x in rows if x['kind']==kind];summary[kind]=base.stats([x['elapsedMs'] for x in subset]);summary[kind]['failed']=sum(x['status']!=200 for x in subset)
    return {'summary':summary,'requests':requests,'reads':rows,'selections':selections}
async def main():
    results=[]
    save('conditions.json',{'scenario':CASE,'revision':os.getenv('GITHUB_SHA'),'cpuCount':os.cpu_count(),'users':N,'capacity':100,'selectedCourse':1,'catalogRows':10000,'baselineSeconds':5,'mixedSeconds':15,'backgroundRpsPerServer':50,'servers':2,'layout':'B split','atomicCapacity':True,'httpTimeoutSeconds':10,'warmup':'same scenario once per fresh app pair, excluded','selection':'Each student performs a real GET while course is open. All pages loaded before submission release. No re-read or alternative course, one attempt. Background read load begins after snapshot preparation.','delay':'common 2s lead then 0..5s / 1..3s / 0..0.1s. Read-to-click age is recorded separately.','redis':'disposable advisory-only Redis, FLUSHDB between runs','comparison':'3 alternating rounds on same runner. Do not compare absolute values with another matrix runner or earlier JMeter runs.'})
    for round_no in range(1,4):
        for enabled in ([False,True] if round_no%2 else [True,False]):
            label=f'{round_no}-{"ON" if enabled else "OFF"}';procs=[];logs=[]
            try:
                for port in PORTS:
                    log=(OUT/f'{label}-{port}.log').open('w');logs.append(log)
                    args=['java','-Xms256m','-Xmx512m','-jar',str(ROOT/'backend/target/db-split-experiment-0.0.1-SNAPSHOT.jar'),f'--server.port={port}','--experiment.mode=B','--experiment.fixture-students=1000','--experiment.dataset-policy=fixed','--experiment.atomic-capacity=true',f'--experiment.closed-precheck={str(enabled).lower()}','--experiment.redis.address=redis://127.0.0.1:56379','--spring.datasource.url=jdbc:mysql://127.0.0.1:53328/enrollment_experiment','--spring.datasource.username=experiment','--spring.datasource.password=experiment-local','--experiment.academic.url=jdbc:mysql://127.0.0.1:53327/enrollment_experiment','--experiment.academic.username=experiment','--experiment.academic.password=experiment-local']
                    procs.append(subprocess.Popen(args,stdout=log,stderr=subprocess.STDOUT))
                async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=10),connector=aiohttp.TCPConnector(limit=0)) as s:
                    for _ in range(90):
                        states=[await base.call(s,p,'/experiment/state') for p in PORTS]
                        if all(x[0]==200 for x in states):break
                        if any(p.poll() is not None for p in procs):raise RuntimeError('Server startup failed')
                        await asyncio.sleep(1)
                    else:raise RuntimeError('Readiness timeout')
                    assert all(x[1]['atomicCapacity'] and x[1]['closedPrecheck']==enabled for x in states)
                    async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=120)) as setup:
                        code,fixture,_=await base.call(setup,PORTS[0],'/experiment/fixtures',{});assert code==200 and fixture['students']==1000,fixture
                    run=fixture['runId'];await reset(s,run)
                    save(label+'-warmup.json',await measure(s,run,await snapshots(s,run),10)) # excluded warm-up
                    await reset(s,run)
                    reads=[];lags=[];await background(s,run,time.perf_counter(),5,reads,lags)
                    selections=await snapshots(s,run)
                    before=[(await base.call(s,p,'/experiment/state'))[1] for p in PORTS]
                    mixed=await measure(s,run,selections)
                    after=[(await base.call(s,p,'/experiment/state'))[1] for p in PORTS]
                    code,db,_=await base.call(s,PORTS[0],f'/experiment/integrity?runId={run}');assert code==200
                    delta={}
                    for b,a in zip(before,after):
                        for key,value in a['metrics']['phases'].items():delta[key]=delta.get(key,0)+value['count']-b['metrics']['phases'].get(key,{}).get('count',0)
                    valid=all(db[k]==0 for k in ['counterMismatches','creditViolations','scheduleViolations','studentsWithMultipleEnrollments']) and db['confirmed']==mixed['summary']['outcomes'].get('CONFIRMED',0) and db['totalCatalogRows']==10000 and db['totalStudentRows']==1000
                    result={'scenario':CASE,'label':label,'round':round_no,'enabled':enabled,'integrityValid':valid,'outcomesComplete':len(mixed['requests'])==N,'mixed':mixed['summary'],'metricCountDelta':delta,'database':db,'before':before,'after':after,'baseline':{k:base.stats([x['elapsedMs'] for x in reads if x['kind']==k]) for k in ['academic','offering']}}
                    save(label+'-raw.json',mixed);save(label+'.json',result);results.append(result);save('results.json',results)
                    print(json.dumps({k:result[k] for k in ['scenario','label','integrityValid','mixed','metricCountDelta']},ensure_ascii=False),flush=True)
                    if not valid:raise RuntimeError('Integrity failure; retain evidence')
            finally:
                for p in procs:
                    if p.poll() is None:p.terminate()
                for p in procs:
                    try:p.wait(timeout=15)
                    except subprocess.TimeoutExpired:p.kill();p.wait()
                for log in logs:log.close()
            await asyncio.sleep(2)
asyncio.run(main())
