/** Node 18+; no npm dependencies. Run from any directory with Docker and both queue-profile servers running. */
import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { ExperimentHttp } from './experiment-http.mjs';
const transport=new ExperimentHttp();
import { randomBytes } from 'node:crypto';
import { performance, monitorEventLoopDelay } from 'node:perf_hooks';
import { setTimeout as delay } from 'node:timers/promises';

export function percentile(values, quantile = .95) {
  if (!values.length) return null;
  const sorted = [...values].sort((a,b)=>a-b);
  return Math.round(sorted[Math.ceil(sorted.length*quantile)-1]*100)/100;
}
export function statistics(values) {
  return { count:values.length, meanMs:values.length ? Math.round(values.reduce((a,b)=>a+b,0)/values.length*100)/100 : null,
    p95Ms:percentile(values), maxMs:values.length ? Math.round(Math.max(...values)*100)/100 : null };
}
function option(name, fallback) {
  const at=process.argv.indexOf(`--${name}`);
  if(at<0)return fallback;
  const value=Number(process.argv[at+1]);
  if(!Number.isInteger(value)||value<1)throw Error(`--${name} requires a positive integer`);
  return value;
}
async function http(url,init={},timeout=10000){return transport.request(url,init,timeout);}
export function transportSummary(samples){
  return Object.fromEntries(['submit','catalog','poll','diagnostic'].map(group=>{
    const rows=samples.filter(s=>s.group===group);
    const metric=key=>statistics(rows.filter(s=>Number.isFinite(s[key])).map(s=>s[key]));
    return [group,{count:rows.length,failed:rows.filter(s=>s.error).length,
      noSocket:rows.filter(s=>s.socketAssignedMs===null).length,reused:rows.filter(s=>s.reusedSocket).length,
      socketAssignedMs:metric('socketAssignedMs'),connectedMs:metric('connectedMs'),
      requestFlushedMs:metric('requestFlushedMs'),headersMs:metric('headersMs'),elapsedMs:metric('elapsedMs')}];
  }));
}
export async function observeRequest(base, studentId, courseId, config) {
  const start=performance.now(), startedAt=Date.now();
  let acceptedAt=null, requestId=null, submitCode=null, polls=0, pollErrors=0;
  try {
    const response=await http(`${base}/api/enrollment-requests`,{
      method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({studentId,courseId})
    },config.httpTimeoutMs);
    submitCode=response.status;
    if(response.status!==202 || !response.body?.requestId) {
      return {studentId,courseId,startedAt,submitCode,outcome:'SUBMIT_FAILED',submitMs:performance.now()-start,polls,pollErrors};
    }
    acceptedAt=performance.now(); requestId=response.body.requestId;
    let state=response.body;
    while(performance.now()-start<config.deadlineMs) {
      if(['CONFIRMED','REJECTED','FAILED'].includes(state.status)) {
        return {studentId,courseId,requestId,startedAt,submitCode,outcome:state.status,message:state.message,
          submitMs:acceptedAt-start,observedResultMs:performance.now()-start,polls,pollErrors};
      }
      await delay(config.pollMs);
      if(performance.now()-start>=config.deadlineMs)break;
      polls++;
      try {
        const result=await http(`${base}/api/enrollment-requests/${requestId}`,{},
          Math.max(1,Math.min(config.httpTimeoutMs,config.deadlineMs-(performance.now()-start))));
        if(result.status===200 && result.body?.requestId===requestId)state=result.body;
        else pollErrors++;
      } catch {pollErrors++;}
    }
    return {studentId,courseId,requestId,startedAt,submitCode,outcome:'UNRESOLVED',submitMs:acceptedAt-start,polls,pollErrors};
  } catch(error) {
    // POST timeout is an unknown outcome: the receipt may already be durable.
    return {studentId,courseId,requestId,startedAt,submitCode,outcome:'SUBMIT_UNKNOWN',
      submitMs:performance.now()-start,error:error.message,polls,pollErrors};
  }
}
export async function observeSynchronous(base,studentId,courseId,config){
  const start=performance.now(),startedAt=Date.now();
  try{
    const result=await http(`${base}/api/enrollments`,{
      method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({studentId,courseId})
    },config.deadlineMs);
    const elapsed=performance.now()-start;
    const businessCodes=['COURSE_FULL','CREDIT_LIMIT_EXCEEDED','SCHEDULE_CONFLICT','DUPLICATE_ENROLLMENT','STUDENT_NOT_ACTIVE'];
    const outcome=result.status===201?'CONFIRMED':result.status===409&&businessCodes.includes(result.body?.code)?'REJECTED':'SUBMIT_FAILED';
    return {studentId,courseId,startedAt,submitCode:result.status,outcome,submitMs:elapsed,
      ...(['CONFIRMED','REJECTED'].includes(outcome)?{observedResultMs:elapsed}:{}),
      message:result.body?.message,code:result.body?.code,polls:0,pollErrors:0};
  }catch(error){return {studentId,courseId,startedAt,outcome:'SUBMIT_UNKNOWN',submitMs:performance.now()-start,error:error.message,polls:0,pollErrors:0};}
}
async function poolLoad(base,config){
  const samples=[],start=performance.now();
  for(let i=0;i<config.readSeconds;i++){
    await delay(Math.max(0,start+i*1000-performance.now()));
    const sampledMs=performance.now()-start;
    if(sampledMs>=config.readSeconds*1000)break;
    if(sampledMs-i*1000>1000)continue;
    try{const r=await http(`${base}/api/experiment/pool`,{},2000);samples.push({sampledMs,code:r.status,...r.body});}
    catch{samples.push({sampledMs,code:'NETWORK_ERROR'});}
  }
  return {base,samples};
}
export async function readLoad(base, config) {
  const samples=[], pending=new Set(),start=performance.now();let skipped=0;
  const count=config.readSeconds*config.readRps;
  for(let i=0;i<count;i++) {
    const scheduled=start+i*1000/config.readRps;
    const wait=scheduled-performance.now();if(wait>0)await delay(wait);
    const lag=performance.now()-scheduled;
    // Do not hide an overloaded generator by releasing a catch-up burst.
    if(lag>1000 || pending.size>=100){skipped++;continue;}
    const began=performance.now();
    const task=(async()=>{
      try {
        const result=await http(`${base}/api/courses?year=2026&semester=2&department=CS&afterId=${100000+(i%100)*100}&size=20`,{},config.httpTimeoutMs);
        samples.push({startedMs:began-start,elapsedMs:performance.now()-began,lagMs:lag,code:result.status});
      } catch(error){samples.push({startedMs:began-start,elapsedMs:performance.now()-began,lagMs:lag,code:'NETWORK_ERROR',error:error.message});}
    })();pending.add(task);task.finally(()=>pending.delete(task));
  }
  await delay(Math.max(0,start+config.readSeconds*1000-performance.now()));
  await Promise.all(pending);
  return {base,samples,skipped,targetRps:config.readRps,actualStartRps:samples.length/config.readSeconds,
    failed:samples.filter(s=>s.code!==200).length,...statistics(samples.map(s=>s.elapsedMs))};
}
export async function phase(name,bases,students,courseId,config) {
  console.log(`${name}: 조회 ${config.readSeconds}초${students.length ? ` + 신청 ${students.length}건/${config.rampSeconds}초` : ''}`);
  const diagnosticSnapshot=async()=>Promise.all(bases.map(async base=>{
    try {const r=await http(`${base}/api/experiment/diagnostics`,{},3000);return {base,...r};}
    catch(error){return {base,error:error.message};}
  }));
  const before=config.collectPool?await diagnosticSnapshot():[];
  transport.drain();
  const loop=monitorEventLoopDelay({resolution:20});loop.enable();
  const cpuStart=process.cpuUsage(),wallStart=performance.now();
  const startedAt=Date.now();
  const reads=Promise.all(bases.map(base=>readLoad(base,config)));
  const pools=config.collectPool?Promise.all(bases.map(base=>poolLoad(base,config))):Promise.resolve([]);
  const results=[];
  if(students.length) {
    await delay(config.enrollmentDelayMs);
    const start=performance.now(),tasks=[];
    for(let i=0;i<students.length;i++) {
      const wait=start+i*config.rampSeconds*1000/students.length-performance.now();if(wait>0)await delay(wait);
      const lagMs=Math.max(0,performance.now()-(start+i*config.rampSeconds*1000/students.length));
      if(lagMs>1000){results.push({studentId:students[i],courseId,outcome:'CLIENT_SKIPPED',lagMs,polls:0,pollErrors:0});continue;}
      const observe=config.mode?.startsWith('pessimistic')?observeSynchronous:observeRequest;
      tasks.push(observe(bases[i%bases.length],students[i],courseId,config).then(result=>results.push({...result,lagMs})));
    }
    await Promise.all(tasks);
  }
  const readResults=await reads,poolResults=await pools;
  loop.disable();
  const cpu=process.cpuUsage(cpuStart);
  const generator={eventLoopP95Ms:loop.percentile(95)/1e6,eventLoopMaxMs:loop.max/1e6,
    cpuMs:(cpu.user+cpu.system)/1000,wallMs:performance.now()-wallStart,rssBytes:process.memoryUsage().rss};
  const endedAt=Date.now();
  const httpSamples=transport.drain();
  const after=config.collectPool?await diagnosticSnapshot():[];
  return {name,startedAt,endedAt,reads:readResults,pools:poolResults,enrollments:results,
    diagnostics:{before,after,generator,transport:transportSummary(httpSamples)},httpSamples};
}
function sql(query, headers=false) {
  return execFileSync('docker',['exec','-i','class-enrollment-mysql','mysql','-uclass_user','-pclass_password',
    '--batch',...(headers?[]:['--skip-column-names']),'class_enrollment'],{input:query,encoding:'utf8',maxBuffer:8*1024*1024,stdio:['pipe','pipe','pipe']}).trim();
}
function save(directory,name,data){writeFileSync(resolve(directory,name),JSON.stringify(data,null,2));}
async function main() {
  const modeAt=process.argv.indexOf('--mode');
  const mode=modeAt<0?'queue':process.argv[modeAt+1];
  if(!['queue','pessimistic','pessimistic-ungated'].includes(mode))throw Error('--mode queue|pessimistic|pessimistic-ungated');
  const config={mode,collectPool:process.argv.includes('--pool'),strict:process.argv.includes('--strict'),users:option('users',500),capacity:option('capacity',100),readSeconds:option('seconds',60),
    readRps:option('read-rps',10),rampSeconds:option('ramp',5),pollMs:2000,deadlineMs:option('deadline',45)*1000,httpTimeoutMs:10000,enrollmentDelayMs:5000};
  if(config.users>10000||config.readRps>200||config.readSeconds>300||config.deadlineMs>180000||config.capacity>config.users)throw Error('Supported: users<=10000, read-rps<=200, seconds<=300, deadline<=180, capacity<=users');
  if(config.readSeconds*1000<config.enrollmentDelayMs+config.rampSeconds*1000+config.deadlineMs)throw Error('--seconds must cover 5s enrollment delay + ramp + deadline');
  const bases=['http://localhost:8080','http://localhost:8081'];
  const serverModes=[];
  for(const base of bases) {
    let poolState=null;
    if(mode!=='queue'||config.collectPool){
      const p=await http(`${base}/api/experiment/pool`);
      if(p.status!==200||typeof p.body?.queueEnabled!=='boolean')throw Error(`${base}: 최신 서버를 --experiment.enabled=true로 실행하세요.`);
      const diagnostic=await http(`${base}/api/experiment/diagnostics`);
      if(diagnostic.status!==200)throw Error(`${base}: 진단 기능이 포함된 최신 JAR로 재시작하세요.`);
      poolState=p.body;
      if(mode==='queue'&&!poolState.queueEnabled)throw Error(`${base}: 큐가 비활성화돼 있습니다.`);
      if(mode!=='queue'&&(poolState.queueEnabled||poolState.strategy!=='PESSIMISTIC'||poolState.gate!==(mode==='pessimistic')))
        throw Error(`${base}: 비교 모드와 서버의 queue/strategy/gate 설정이 다릅니다.`);
    }
    if(mode==='queue'){
      const health=await http(`${base}/api/enrollment-requests/health`);
      if(health.status!==200 || health.body.mode!=="redis-ingress-v1")throw Error(`${base}: Redis 접수 서버가 아닙니다.`);
      serverModes.push({base,...health.body,pool:poolState});
      if(health.body.pending)throw Error(`${base}: 미완료 Redis 접수가 있습니다. 새 실험 전에 처리 상태를 확인하세요.`);
    }else serverModes.push({base,mode,pool:poolState});
    const read=await http(`${base}/api/courses?year=2026&semester=2&department=CS&afterId=100000&size=20`);
    if(read.status!==200||read.body?.items?.length!==20)throw Error(`${base}: 이전 catalog 실험 데이터가 필요합니다. 기존 초기화 스크립트를 임의로 실행하지 말고 데이터 상태를 확인하세요.`);
  }
  if(serverModes.some(h=>h.version!==serverModes[0].version || h.batchReject!==serverModes[0].batchReject || h.pool?.max!==serverModes[0].pool?.max || h.pool?.precheck!==serverModes[0].pool?.precheck))throw Error("두 서버의 버전 또는 batchReject 설정이 다릅니다.");
  const catalogCount=Number(sql("SELECT COUNT(*) FROM courses WHERE academic_year=2026 AND semester=2 AND department='CS';"));
  const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');
  const run=`Q${randomBytes(6).toString('hex')}`;
  const directory=resolve(root,'jmeter','results',`${mode}-${new Date().toISOString().replace(/[:.]/g,'-')}-${run}`);
  mkdirSync(directory,{recursive:true});
  let revision='unknown';try{revision=execFileSync('git',['rev-parse','HEAD'],{cwd:root,encoding:'utf8'}).trim();}catch{}
  save(directory,'conditions.json',{...config,run,revision,bases,catalogCount,serverModes,transport:{name:'node-http-keepalive-v1',limits:transport.limits},
    note:'Node HTTP client. Same read arrival schedule in both phases. Terminal time includes polling delay; no claim of simultaneous arrival.'});
  // Independent identities for each run. Never truncate/delete the user's enrollment or catalog data.
  const values=Array.from({length:config.users},(_,i)=>`('${run}${i}','Queue ${i}','ACTIVE',18)`).join(',');
  const fixture=`START TRANSACTION; INSERT INTO students(student_number,name,status,max_credits) VALUES ${values};\n`+
    `INSERT INTO courses(course_code,name,professor,department,academic_year,semester,credits,capacity,enrolled_count) VALUES ('${run}','Queue experiment','Queue','QUEUE_TEST',2026,2,3,${config.capacity},0); COMMIT;`;
  writeFileSync(resolve(directory,'fixture.sql'),fixture);sql(fixture);
  const students=sql(`SELECT id FROM students WHERE student_number LIKE '${run}%' ORDER BY id;`).split('\n').filter(Boolean).map(Number);
  const courseId=Number(sql(`SELECT id FROM courses WHERE course_code='${run}' AND academic_year=2026 AND semester=2;`));
  if(students.length!==config.users||!courseId)throw Error('Fixture identity check failed');
  save(directory,'cohort.json',{students,courseId,run});
  console.log(`모드: ${mode}, 신청 ${config.users}건/${config.rampSeconds}초, 조회 서버당 ${config.readRps}RPS\n결과 저장: ${directory}\n기존 데이터 삭제 없음. 조회 예열 후 baseline/mixed를 각각 ${config.readSeconds}초 측정합니다.`);
  for(const base of bases)for(let i=0;i<5;i++)await http(`${base}/api/courses?year=2026&semester=2&department=CS&afterId=100000&size=20`);
  const baseline=await phase('Baseline',bases,[],courseId,config);save(directory,'baseline.json',baseline);
  const mixed=await phase('Mixed',bases,students,courseId,config);save(directory,'mixed.json',mixed);
  const windowFromMs=config.enrollmentDelayMs;
  const windowToMs=config.enrollmentDelayMs+config.rampSeconds*1000+config.deadlineMs;
  const starts=mixed.enrollments.filter(e=>Number.isFinite(e.startedAt)).map(e=>e.startedAt);
  const summary={readWindow:{fromMs:windowFromMs,toMs:windowToMs},
    reads:[...baseline.reads.map(r=>({phase:'Baseline',...r})),...mixed.reads.map(r=>({phase:'Mixed',...r}))].map(r=>{
      const samples=r.samples.filter(s=>s.startedMs>=windowFromMs && s.startedMs<windowToMs);
      return {phase:r.phase,base:r.base,targetRps:r.targetRps,actualStartRps:samples.length/((windowToMs-windowFromMs)/1000),
        skipped:r.skipped,failed:samples.filter(s=>s.code!==200).length,...statistics(samples.map(s=>s.elapsedMs))};
    }),
    mode,
    submit:statistics(mixed.enrollments.filter(e=>Number.isFinite(e.submitMs)).map(e=>e.submitMs)),
    acceptedSubmit:statistics(mixed.enrollments.filter(e=>e.submitCode===202).map(e=>e.submitMs)),
    terminalByOutcome:Object.fromEntries(['CONFIRMED','REJECTED','FAILED'].map(status=>[status,statistics(mixed.enrollments.filter(e=>e.outcome===status).map(e=>e.observedResultMs))])),
    outcomes:Object.fromEntries(['CONFIRMED','REJECTED','FAILED','UNRESOLVED','SUBMIT_FAILED','SUBMIT_UNKNOWN','CLIENT_SKIPPED'].map(status=>[status,mixed.enrollments.filter(e=>e.outcome===status).length])),
    pollErrors:mixed.enrollments.reduce((sum,e)=>sum+e.pollErrors,0),
    submitStartSpreadMs:starts.length?Math.max(...starts)-Math.min(...starts):null};
  const [capacity,enrolled,actual,confirmedReceipts]=sql(`SELECT c.capacity,c.enrolled_count,
    (SELECT COUNT(*) FROM enrollments e WHERE e.course_id=c.id),
    (SELECT COUNT(*) FROM enrollment_ingress_results r WHERE r.course_id=c.id AND r.status='CONFIRMED')
    FROM courses c WHERE c.id=${courseId};`).split('\t').map(Number);
  summary.database={capacity,enrolled,actual,confirmedReceipts,consistent:enrolled===actual && (mode!=='queue'||actual===confirmedReceipts) && actual<=capacity};
  summary.burstReads=mixed.reads.map(r=>{
    const samples=r.samples.filter(s=>s.startedMs>=config.enrollmentDelayMs&&s.startedMs<config.enrollmentDelayMs+config.rampSeconds*1000);
    return {base:r.base,failed:samples.filter(s=>s.code!==200).length,...statistics(samples.map(s=>s.elapsedMs))};
  });
  summary.pool=mixed.pools.map(p=>{
    const valid=p.samples.filter(s=>s.code===200);
    return {base:p.base,samples:valid.length,failed:p.samples.length-valid.length,
      maxActive:valid.length?Math.max(...valid.map(s=>s.active)):null,maxWaiting:valid.length?Math.max(...valid.map(s=>s.waiting)):null};
  });
  summary.diagnostics=mixed.diagnostics;
  summary.generator={...mixed.diagnostics.generator,maxEnrollmentLagMs:Math.max(...mixed.enrollments.map(e=>e.lagMs??0)),skippedEnrollment:summary.outcomes.CLIENT_SKIPPED};
  summary.responseCodes=Object.fromEntries([...new Set(mixed.enrollments.map(e=>String(e.submitCode??e.outcome)))].map(code=>[code,mixed.enrollments.filter(e=>String(e.submitCode??e.outcome)===code).length]));
  save(directory,'summary.json',summary);
  const verification=sql(`SELECT c.id,c.capacity,c.enrolled_count,(SELECT COUNT(*) FROM enrollments e WHERE e.course_id=c.id) AS actual_count FROM courses c WHERE c.id=${courseId}; SELECT status,COUNT(*) AS count FROM enrollment_ingress_results WHERE course_id=${courseId} GROUP BY status;`,true);
  writeFileSync(resolve(directory,'database.txt'),verification+'\n');
  console.table(summary.reads.map(r=>({phase:r.phase,server:r.base,count:r.count,p95Ms:r.p95Ms,failed:r.failed,actualRps:r.actualStartRps.toFixed(2),skipped:r.skipped})));
  if(mode==='queue')console.log('접수 응답 (202 응답만):',summary.acceptedSubmit);
  console.log('HTTP 연결 진단:',JSON.stringify(summary.diagnostics.transport,null,2));
  console.log('신청이 몰린 구간의 조회 응답:');console.table(summary.burstReads);
  console.table(summary.pool);console.log('응답 코드:',summary.responseCodes,'부하 생성:',summary.generator);
  console.table(summary.terminalByOutcome);console.table(summary.outcomes);console.log(verification);
  if(summary.outcomes.UNRESOLVED||summary.outcomes.SUBMIT_UNKNOWN)console.warn('미완료/응답 불명 요청이 있습니다. 처리는 서버에서 계속될 수 있으며 database.txt는 측정 종료 시점의 별도 상태입니다. 재실행 전에 미완료 상태를 확인하세요.');
  if(summary.reads.some(r=>r.skipped||r.failed))console.warn('조회 실패 또는 부하 생성 지연이 있습니다. 원시 결과를 확인하세요.');
  if(mixed.enrollments.some(e=>e.observedResultMs && e.startedAt+e.observedResultMs>mixed.startedAt+config.readSeconds*1000))console.warn('최종 결과 확인이 고정 조회 측정 구간을 넘었습니다. 조회 지연만으로 전체 처리 구간을 평가하지 마세요.');
  if(!summary.database.consistent)throw Error(`DB 정합성 확인 실패: ${JSON.stringify(summary.database)}. 결과: ${directory}`);
  console.log(`Saved: ${directory}`);
  if(config.strict&&(summary.outcomes.UNRESOLVED||summary.outcomes.SUBMIT_UNKNOWN||summary.outcomes.SUBMIT_FAILED||summary.outcomes.FAILED||summary.outcomes.CLIENT_SKIPPED||[...baseline.reads,...mixed.reads].some(r=>r.skipped||r.failed)||summary.pool.some(p=>p.failed))){
    console.warn('부하 한계 또는 측정 불확실성이 확인됐습니다. 결과를 보존하고 자동 증량을 중단합니다.');process.exitCode=2;
  }
}
if(process.argv[1] && resolve(process.argv[1])===fileURLToPath(import.meta.url))main().catch(error=>{console.error(error.message);process.exitCode=1;}).finally(()=>transport.close());
