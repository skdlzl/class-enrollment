import { mkdirSync, writeFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomBytes } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { http, sql, phase, statistics, transportSummary, closeExperimentTransport } from './run-queue-experiment.mjs';
import { validateSyncLockMode } from './sync-lock-modes.mjs';
import { ExperimentHttp } from './experiment-http.mjs';
const journeyTransport=new ExperimentHttp({submit:250,catalog:250,poll:1,diagnostic:1});
import { observeJourney } from './enrollment-journey.mjs';

async function main(){
  const strategy=process.argv[2], warmup=process.argv.includes('--warmup');
  if(!['REDIS','PESSIMISTIC'].includes(strategy)||process.argv.slice(3).some(x=>x!=='--warmup'))throw Error('Usage: node scripts/run-enrollment-journey.mjs REDIS|PESSIMISTIC [--warmup]');
  const mode=`sync-${strategy.toLowerCase()}-precheck`,bases=['http://localhost:8080','http://localhost:8081'];
  const servers=[];
  for(const base of bases){
    console.log(`준비: ${base} 서버 설정 및 조회 API 확인`);
    const state=await http(`${base}/api/experiment/pool`);
    if(state.status!==200)throw Error(`${base}: experiment endpoint unavailable`);
    validateSyncLockMode(mode,state.body);servers.push({base,...state.body});
    const catalog=await http(`${base}/api/courses?year=2026&semester=2&department=CS&afterId=100000&size=20`);
    if(catalog.status!==200||catalog.body?.items?.length!==20)throw Error('기존 catalog 실험 데이터가 필요합니다');
  }
  const run=`J${randomBytes(6).toString('hex')}`;
  const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');
  const directory=resolve(root,'jmeter/results',`journey-${strategy}-${warmup?'warmup':'measured'}-${new Date().toISOString().replace(/[:.]/g,'-')}-${run}`);
  mkdirSync(directory,{recursive:true});
  const save=(name,data)=>writeFileSync(resolve(directory,name),JSON.stringify(data,null,2));
  const studentsSql=Array.from({length:500},(_,i)=>`('${run}${i}','Journey ${i}','ACTIVE',18)`).join(',');
  const coursesSql=Array.from({length:5},(_,i)=>`('${run}C${i}','Journey ${i}','Test','${run}',2026,2,3,100,0)`).join(',');
  const fixture=`START TRANSACTION; INSERT INTO students(student_number,name,status,max_credits) VALUES ${studentsSql}; INSERT INTO courses(course_code,name,professor,department,academic_year,semester,credits,capacity,enrolled_count) VALUES ${coursesSql}; COMMIT;`;
  console.log('준비: 실험용 학생 500명과 과목 5개 생성');
  writeFileSync(resolve(directory,'fixture.sql'),fixture);sql(fixture);
  const students=sql(`SELECT id FROM students WHERE student_number LIKE '${run}%' ORDER BY id`).split('\n').map(Number);
  const courseIds=sql(`SELECT id FROM courses WHERE department='${run}' ORDER BY id`).split('\n').map(Number);
  if(students.length!==500||courseIds.length!==5)throw Error('Fixture mismatch');
  const config={mode,users:500,department:run,courseIds,studentIndexes:Object.fromEntries(students.map((id,i)=>[id,i])),
    collectPool:true,readSeconds:90,readRps:10,rampSeconds:5,deadlineMs:60000,httpTimeoutMs:10000,enrollmentDelayMs:5000};
  save('conditions.json',{...config,servers,warmup,measurementVersion:'journey-v2-isolated-client',journeyTransport:journeyTransport.limits,backgroundTransport:{submit:128,catalog:16,poll:32,diagnostic:2},revision:execFileSync('git',['rev-parse','HEAD'],{cwd:root,encoding:'utf8'}).trim(),
    policy:'5 courses x 100 seats; 500 users; at most 3 submissions. First available course then deterministic alternatives. No think time. Background CS queries 10 RPS/server. Students each stop after first success. Student Redis lock shared in both strategies.'});
  save('cohort.json',{students,courseIds});
  console.log(`결과 저장: ${directory}\n조회 → 신청 → 마감 시 재조회, 최대 3회 신청. ${warmup?'예열 실행(비교 제외)':'본 측정'}`);
  for(const base of bases)for(let i=0;i<5;i++)await http(`${base}/api/courses?year=2026&semester=2&department=CS&afterId=100000&size=20`);
  const baseline=await phase('Baseline',bases,[],null,config);save('baseline.json',baseline);
  const mixed=await phase('Journey',bases,students,null,{...config,observe:(...args)=>observeJourney(...args,(...requestArgs)=>journeyTransport.request(...requestArgs))});mixed.journeyHttpSamples=journeyTransport.drain();save('mixed.json',mixed);
  const rows=mixed.enrollments, steps=rows.flatMap(r=>r.steps??[]);
  const outcomes=Object.fromEntries([...new Set(rows.map(r=>r.outcome))].map(o=>[o,rows.filter(r=>r.outcome===o).length]));
  const database=sql(`SELECT c.id,c.capacity,c.enrolled_count,(SELECT COUNT(*) FROM enrollments e WHERE e.course_id=c.id) FROM courses c WHERE department='${run}' ORDER BY c.id`).split('\n').map(line=>{const [id,capacity,enrolled,actual]=line.split('\t').map(Number);return {id,capacity,enrolled,actual};});
  const duplicateStudents=Number(sql(`SELECT COUNT(*) FROM (SELECT e.student_id FROM enrollments e JOIN courses c ON e.course_id=c.id WHERE c.department='${run}' GROUP BY e.student_id HAVING COUNT(*)>1) d`));
  const failures=steps.filter(s=>s.kind==='submit'&&s.status!==201&&!(s.status===409&&s.code==='COURSE_FULL'));
  const failureGroups=Object.values(failures.reduce((groups,s)=>{const key=JSON.stringify([s.status,s.code,s.message,s.error]);groups[key]??={status:s.status,code:s.code,message:s.message,error:s.error,count:0};groups[key].count++;return groups;},{}));
  const journeyTiming=Object.fromEntries(['read','submit'].map(kind=>{const samples=steps.filter(s=>s.kind===kind&&s.timing);return [kind,{socketAssignedMs:statistics(samples.map(s=>s.timing.socketAssignedMs).filter(Number.isFinite)),afterSocketMs:statistics(samples.filter(s=>Number.isFinite(s.timing.socketAssignedMs)).map(s=>s.timing.elapsedMs-s.timing.socketAssignedMs))}];}));
  const summary={strategy,warmup,measurementVersion:'journey-v2-isolated-client',failureGroups,journeyTiming,journeyTransport:transportSummary(mixed.journeyHttpSamples),outcomes,successRate:(outcomes.CONFIRMED??0)/500,
    successfulJourney:statistics(rows.filter(r=>r.outcome==='CONFIRMED').map(r=>r.journeyMs)),
    journeyByOutcome:Object.fromEntries(Object.keys(outcomes).map(o=>[o,statistics(rows.filter(r=>r.outcome===o&&Number.isFinite(r.journeyMs)).map(r=>r.journeyMs))])),
    journeyReads:statistics(steps.filter(s=>s.kind==='read').map(s=>s.elapsedMs)),
    enrollmentAttempts:statistics(steps.filter(s=>s.kind==='submit').map(s=>s.elapsedMs)),
    retryUsers:rows.filter(r=>r.attempts>1).length,database,duplicateStudents,
    databaseConsistent:database.every(d=>d.enrolled===d.actual&&d.actual<=d.capacity)&&duplicateStudents===0,
    clientConfirmed:outcomes.CONFIRMED??0,dbConfirmed:database.reduce((sum,d)=>sum+d.actual,0),
    backgroundReads:[...baseline.reads.map(r=>({phase:'Baseline',...r})),...mixed.reads.map(r=>({phase:'Mixed',...r}))].map(({samples,...r})=>r),
    pool:mixed.pools.map(p=>({base:p.base,failed:p.samples.filter(s=>s.code!==200).length,maxWaiting:Math.max(0,...p.samples.filter(s=>s.code===200).map(s=>s.waiting))})),diagnostics:mixed.diagnostics};
  save('summary.json',summary);
  console.table(failureGroups);console.log('사용자 HTTP 대기(연결 배정 이후에도 네트워크/서버 처리 포함):',JSON.stringify(journeyTiming,null,2));
  console.table(outcomes);console.log('첫 조회부터 신청 성공까지:',summary.successfulJourney);console.table(database);
  console.table(summary.backgroundReads.map(r=>({phase:r.phase,base:r.base,p95Ms:r.p95Ms,failed:r.failed,skipped:r.skipped})));
  console.log(`Saved: ${directory}`);
  if(!summary.databaseConsistent||summary.clientConfirmed!==summary.dbConfirmed||['READ_FAILED','SUBMIT_FAILED','SUBMIT_UNKNOWN','DEADLINE','CLIENT_SKIPPED'].some(o=>outcomes[o])||summary.backgroundReads.some(r=>r.failed||r.skipped)||summary.pool.some(p=>p.failed)){
    console.warn('오류/측정 불확실성이 있습니다. 결과를 보존하고 원인을 확인하세요.');process.exitCode=2;
  }
}
main().catch(e=>{console.error(e.message);process.exitCode=1;}).finally(()=>{closeExperimentTransport();journeyTransport.close();});
