/** Same offered load for queued Redis ingress and synchronous pessimistic-course-lock modes. */
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
const value=(name,fallback)=>{const i=process.argv.indexOf(name);return i<0?fallback:process.argv[i+1];};
const mode=value('--mode','queue');
const stage=value('--stage','all');
if(!['queue','pessimistic','pessimistic-ungated'].includes(mode)||!['all','1000','5000','10000'].includes(stage)){
  console.error('Usage: node scripts/run-enrollment-stress.mjs --mode queue|pessimistic|pessimistic-ungated --stage all|1000|5000|10000');
  process.exit(1);
}
const stages=stage==='all'?[1000,5000,10000]:[Number(stage)];
for(const users of stages){
  console.log(`\n===== ${mode}: ${users}건/5초, 정원100, 조회 서버당50RPS =====`);
  const result=spawnSync(process.execPath,[fileURLToPath(new URL('./run-queue-experiment.mjs',import.meta.url)),
    '--mode',mode,'--users',String(users),'--capacity','100','--ramp','5','--read-rps','50',
    '--seconds','90','--deadline','60','--pool','--strict'],{stdio:'inherit'});
  if(result.error||result.status!==0){
    console.error('다음 부하 단계는 중단합니다. 방금 저장된 결과에서 서버 한계와 부하 생성기 한계를 구분하세요.');
    process.exitCode=result.status||1;break;
  }
}
