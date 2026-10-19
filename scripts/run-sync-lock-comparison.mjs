import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
const mode=process.argv[2];
if(!['REDIS','PESSIMISTIC'].includes(mode)){
  console.error('Usage: node scripts/run-sync-lock-comparison.mjs REDIS|PESSIMISTIC');
  process.exit(1);
}
console.log('동기 과목 락 비교: 학생 Redisson 락 공통, 로컬 대기열/사전 정원 검사 OFF, 큐 OFF');
const result=spawnSync(process.execPath,[fileURLToPath(new URL('./run-queue-experiment.mjs',import.meta.url)),
  '--mode',mode==='REDIS'?'sync-redis':'sync-pessimistic','--users','500','--capacity','100',
  '--ramp','5','--read-rps','10','--seconds','90','--deadline','60','--pool','--strict'],{stdio:'inherit'});
if(result.error)console.error(result.error.message);
process.exitCode=result.status??1;
