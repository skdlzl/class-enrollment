import {mkdirSync,writeFileSync,appendFileSync} from 'node:fs';
import {resolve,dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
import {randomBytes} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {http,sql,phase,statistics,transportSummary,closeExperimentTransport} from './run-queue-experiment.mjs';
import {ExperimentHttp} from './experiment-http.mjs';
import {validateSyncLockMode} from './sync-lock-modes.mjs';
import {summarizeEnrollmentPhases} from './enrollment-phase-summary.mjs';
import {scenarios,observeStudentGroup,assessIntegrity} from './student-contention.mjs';
const transport=new ExperimentHttp({submit:250,catalog:100,poll:1,diagnostic:1});

async function main(){
  const args=process.argv.slice(2),strategy=args.shift();
  let warmup=false,control=false,scenario='valid';
  for(let i=0;i<args.length;i++){
    if(args[i]==='--warmup')warmup=true;
    else if(args[i]==='--control')control=true;
    else if(args[i]==='--scenario')scenario=args[++i];
    else throw Error(`Unknown argument: ${args[i]}`);
  }
  if(!['PESSIMISTIC','DB_ONLY'].includes(strategy)||!scenarios[scenario]||control&&scenario!=='valid')
    throw Error('Usage: node scripts/run-student-contention.mjs PESSIMISTIC|DB_ONLY [--warmup] [--control] [--scenario valid|credit|schedule]');
  const bases=['http://localhost:8080','http://localhost:8081'],servers=[];
  for(const base of bases){
    const r=await http(`${base}/api/experiment/pool`);
    if(r.status!==200)throw Error(`${base}: experiment endpoint unavailable`);
    validateSyncLockMode(`sync-${strategy.toLowerCase()}-precheck`,r.body);
    if(r.body.queryPoolEnabled||r.body.dbAdmission?.limit!==0)throw Error(`${base}: query pool and DB admission must be OFF`);
    const d=await http(`${base}/api/experiment/diagnostics`);
    if(!d.body?.enrollmentTimings?.enabled)throw Error(`${base}: phase timing must be enabled`);
    servers.push({base,...r.body});
  }
  const root=resolve(dirname(fileURLToPath(import.meta.url)),'..'),run=`S${randomBytes(6).toString('hex')}`;
  const directory=resolve(root,'jmeter/results',`student-contention-${strategy}-${scenario}-${control?'control':'same-student'}-${warmup?'warmup':'measured'}-${new Date().toISOString().replace(/[:.]/g,'-')}-${run}`);
  mkdirSync(directory,{recursive:true});
  const save=(name,value)=>writeFileSync(resolve(directory,name),JSON.stringify(value,null,2));
  const execute=query=>{appendFileSync(resolve(directory,'fixture.sql'),query+'\n');return sql(query);};
  console.log(`결과 저장: ${directory}\n준비: ${control?500:100}명, 신청 대상 500과목, 검색용 포함 총 10,000과목. 기존 데이터 삭제 없음.`);
  execute(`INSERT INTO students(student_number,name,status,max_credits) VALUES ${Array.from({length:control?500:100},(_,i)=>`('${run}${i}','Student ${i}','ACTIVE',${scenarios[scenario].maxCredits})`).join(',')};`);
  // Each cohort has five private targets plus 95 browsing courses. No shared hot course.
  for(let g=0;g<100;g++){
    const values=Array.from({length:100},(_,j)=>`('${run}C${g*100+j}','${j<5?'StudentTarget':'Browse'} ${j}','Test','${run}D${g}',2026,2,3,100,0)`).join(',');
    execute(`INSERT INTO courses(course_code,name,professor,department,academic_year,semester,credits,capacity,enrolled_count) VALUES ${values};`);
  }
  const studentRows=sql(`SELECT id FROM students WHERE student_number LIKE '${run}%' ORDER BY id`).split('\n').map(Number);
  const targets=sql(`SELECT id,department FROM courses WHERE course_code LIKE '${run}C%' AND name LIKE 'StudentTarget%' ORDER BY id`).split('\n').map(line=>{const [id,department]=line.split('\t');return {id:Number(id),department};});
  if(studentRows.length!==(control?500:100)||targets.length!==500)throw Error('Fixture mismatch');
  const groups=Array.from({length:100},(_,index)=>({index,department:`${run}D${index}`,courseIds:targets.filter(c=>c.department===`${run}D${index}`).map(c=>c.id),studentIds:Array.from({length:5},(_,j)=>studentRows[control?index*5+j:index])}));
  if(groups.some(g=>g.courseIds.length!==5))throw Error('Target group mismatch');
  const schedules=groups.flatMap(g=>g.courseIds.map((id,j)=>{const hour=9+(scenario==='schedule'&&j===1?0:j);return `(${id},'MONDAY','${hour}:00:00','${hour+1}:00:00')`;}));
  execute(`INSERT INTO course_schedules(course_id,day_of_week,start_time,end_time) VALUES ${schedules.join(',')};`);
  const config={mode:`sync-${strategy.toLowerCase()}-precheck`,collectPool:true,readSeconds:90,readRps:50,rampSeconds:5,deadlineMs:35000,httpTimeoutMs:10000,enrollmentDelayMs:5000,
    readUrl:(base,i)=>`${base}/api/courses?${new URLSearchParams({year:'2026',semester:'2',department:groups[i%100].department,size:'50'})}`};
  const databaseSize=sql('SELECT (SELECT COUNT(*) FROM courses),(SELECT COUNT(*) FROM students),(SELECT COUNT(*) FROM enrollments)').split('\t').map(Number);
  save('conditions.json',{...config,databaseSize:{courses:databaseSize[0],students:databaseSize[1],enrollments:databaseSize[2]},strategy,warmup,scenario,control,servers,revision:execFileSync('git',['rev-parse','HEAD'],{cwd:root,encoding:'utf8'}).trim(),measurementVersion:'student-contention-v1',students:studentRows.length,requests:500,catalogCount:10000,
    totalPoolPerServer:10,queryPoolSize:0,gate:false,dbConcurrency:0,transport:transport.limits,
    policy:'100 cohorts start over 5s. Each reads two pages and a target search, then submits five distinct courses concurrently across two JVMs. Targets are private to a cohort to isolate student contention. No retry or synthetic server delay. Control uses five distinct students per cohort. Not the previous popular-course journey workload.'});
  save('cohort.json',groups);
  for(const base of bases){
    const r=await http(`${base}/api/courses?${new URLSearchParams({year:'2026',semester:'2',department:groups[0].department,keyword:'StudentTarget',size:'50'})}`);
    if(r.status!==200||!groups[0].courseIds.every(id=>r.body?.items?.some(c=>c.id===id&&c.enrolledCount===0)))throw Error('Search preflight failed');
  }
  console.log(`${warmup?'예열(비교 제외)':'본 측정'}: 100묶음/5초, 묶음당 조회 3회 후 신청 5건 동시 발송. 배경 조회 서버당 50RPS.`);
  const baseline=await phase('Baseline',bases,[],null,config);save('baseline.json',baseline);
  const mixed=await phase('Student groups',bases,groups.map(g=>g.index),null,{...config,observe:(_base,index)=>observeStudentGroup(groups[index],bases,(...a)=>transport.request(...a),scenario)});
  mixed.studentHttpSamples=transport.drain();save('mixed.json',mixed);
  const rows=mixed.enrollments,requests=rows.flatMap(r=>r.requests??[]),ids=targets.map(c=>c.id).join(','),sids=studentRows.join(',');
  const database=sql(`SELECT c.id,c.capacity,c.enrolled_count,COUNT(e.id) FROM courses c LEFT JOIN enrollments e ON e.course_id=c.id WHERE c.id IN (${ids}) GROUP BY c.id ORDER BY c.id`).split('\n').map(line=>{const [id,capacity,enrolled,actual]=line.split('\t').map(Number);return {id,capacity,enrolled,actual};});
  const students=sql(`SELECT s.id,s.max_credits,COALESCE(SUM(c.credits),0) FROM students s LEFT JOIN enrollments e ON e.student_id=s.id LEFT JOIN courses c ON c.id=e.course_id WHERE s.id IN (${sids}) GROUP BY s.id`).split('\n').map(line=>{const [id,maxCredits,credits]=line.split('\t').map(Number);return {id,maxCredits,credits};});
  const overlaps=Number(sql(`SELECT COUNT(*) FROM enrollments a JOIN enrollments b ON a.student_id=b.student_id AND a.id<b.id JOIN course_schedules x ON x.course_id=a.course_id JOIN course_schedules y ON y.course_id=b.course_id AND x.day_of_week=y.day_of_week AND x.start_time<y.end_time AND y.start_time<x.end_time WHERE a.student_id IN (${sids})`));
  const duplicates=Number(sql(`SELECT COUNT(*) FROM (SELECT student_id,course_id FROM enrollments WHERE student_id IN (${sids}) GROUP BY student_id,course_id HAVING COUNT(*)>1) d`));
  const outcomes=requests.reduce((a,r)=>(a[r.outcome]=(a[r.outcome]??0)+1,a),{});
  const integrity=assessIntegrity(database,students,overlaps,duplicates,outcomes.CONFIRMED??0,100*scenarios[scenario].expectedPerStudent);
  const backgroundReads=[...baseline.reads.map(r=>({phase:'Baseline',...r})),...mixed.reads.map(r=>({phase:'Mixed',...r}))].map(({samples,...r})=>r);
  const summary={strategy,warmup,scenario,control,measurementVersion:'student-contention-v1',outcomes,integrity,
    plannedRequests:500,attemptedRequests:requests.length,unattemptedRequests:500-requests.length,
    incompleteGroups:rows.filter(r=>r.outcome!=='COMPLETED'),allRequests:statistics(requests.map(r=>r.elapsedMs)),
    requestByOutcome:Object.fromEntries(Object.keys(outcomes).map(k=>[k,statistics(requests.filter(r=>r.outcome===k).map(r=>r.elapsedMs))])),
    groupCompletion:statistics(rows.filter(r=>r.outcome==='COMPLETED').map(r=>r.groupMs)),
    maxGroupLaunchSpreadMs:Math.max(0,...rows.map(r=>Math.max(0,...(r.requests??[]).map(q=>q.launchOffsetMs)))),
    failureGroups:requests.filter(r=>['SUBMIT_FAILED','SUBMIT_UNKNOWN'].includes(r.outcome)),
    database,students,overlaps,backgroundReads,phaseTimings:summarizeEnrollmentPhases(mixed.diagnostics),
    transport:transportSummary(mixed.studentHttpSamples),diagnostics:mixed.diagnostics,
    pool:mixed.pools.map(p=>({base:p.base,failed:p.samples.filter(s=>s.code!==200).length,maxActive:Math.max(0,...p.samples.filter(s=>s.code===200).map(s=>s.active)),maxWaiting:Math.max(0,...p.samples.filter(s=>s.code===200).map(s=>s.waiting))}))};
  summary.valid=integrity.databaseConsistent&&integrity.clientDatabaseMatch&&integrity.expectedCountMatch&&requests.length===500&&!summary.failureGroups.length&&!summary.incompleteGroups.length&&!backgroundReads.some(r=>r.failed||r.skipped)&&!summary.pool.some(p=>p.failed)&&!summary.phaseTimings.some(p=>p.error);
  save('summary.json',summary);
  console.table(outcomes);console.log('정합성:',integrity,'신청 응답:',summary.allRequests,'5건 모두 응답까지:',summary.groupCompletion);
  console.table(backgroundReads.map(({phase,base,p95Ms,failed,skipped,actualStartRps})=>({phase,base,p95Ms,failed,skipped,actualStartRps})));
  console.table(summary.pool);console.table(summary.phaseTimings);console.log(`Saved: ${directory}`);
  if(!summary.valid){console.warn('오류/전송 생략/정합성 또는 예상 완료 수 불일치. 결과를 보존했습니다.');process.exitCode=2;}
}
main().catch(e=>{console.error(e.message);process.exitCode=1;}).finally(()=>{transport.close();closeExperimentTransport();});
