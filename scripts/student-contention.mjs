import { performance } from 'node:perf_hooks';

export const scenarios = {
  valid: { maxCredits: 18, expectedPerStudent: 5, rejection: null },
  credit: { maxCredits: 9, expectedPerStudent: 3, rejection: 'CREDIT_LIMIT_EXCEEDED' },
  schedule: { maxCredits: 18, expectedPerStudent: 4, rejection: 'SCHEDULE_CONFLICT' }
};

// A group reads first, then releases five requests without awaiting individual replies.
// Alternating origins guarantees the same student's requests cross both JVMs.
export async function observeStudentGroup(group, bases, request, scenario) {
  const start=performance.now(), steps=[];
  try {
    let cursor=0, result;
    for(let page=0;page<3;page++) {
      const params=new URLSearchParams({year:'2026',semester:'2',department:group.department,size:'50'});
      if(page===1)params.set('afterId',String(cursor));
      if(page===2)params.set('keyword','StudentTarget');
      result=await request(`${bases[group.index%2]}/api/courses?${params}`,{},10000);
      steps.push({kind:'read',status:result.status,timing:result.timing});
      if(result.status!==200||!Array.isArray(result.body?.items))throw Error('Catalogue read failed');
      cursor=result.body.nextAfterId??0;
    }
    if(!group.courseIds.every(id=>result.body.items.some(c=>c.id===id&&c.enrolledCount===0)))
      throw Error('Search did not return all five fresh targets');
  } catch(error) {
    return {index:group.index,outcome:'READ_FAILED',error:error.message,steps,requests:[],groupMs:performance.now()-start};
  }
  const launch=performance.now();
  const requests=await Promise.all(group.courseIds.map(async(courseId,i)=>{
    const studentId=group.studentIds[i],base=bases[(group.index+i)%bases.length],began=performance.now();
    try {
      const result=await request(`${base}/api/enrollments`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({studentId,courseId})},30000);
      const outcome=result.status===201?'CONFIRMED':result.status===409&&result.body?.code===scenarios[scenario].rejection?'EXPECTED_REJECTION':'SUBMIT_FAILED';
      return {base,studentId,courseId,outcome,status:result.status,code:result.body?.code,message:result.body?.message,
        elapsedMs:performance.now()-began,launchOffsetMs:began-launch,timing:result.timing};
    } catch(error) {
      return {base,studentId,courseId,outcome:'SUBMIT_UNKNOWN',error:error.message,elapsedMs:performance.now()-began,launchOffsetMs:began-launch};
    }
  }));
  return {index:group.index,outcome:'COMPLETED',steps,requests,groupMs:performance.now()-start};
}

export function assessIntegrity(database, students, overlaps, duplicates, confirmed, expectedConfirmed) {
  const capacitySafe=database.every(c=>c.actual<=c.capacity&&c.actual===c.enrolled);
  const creditSafe=students.every(s=>s.credits<=s.maxCredits);
  const dbConfirmed=database.reduce((n,c)=>n+c.actual,0);
  return {capacitySafe,creditSafe,scheduleSafe:overlaps===0,duplicates,dbConfirmed,clientConfirmed:confirmed,
    databaseConsistent:capacitySafe&&creditSafe&&overlaps===0&&duplicates===0,
    clientDatabaseMatch:confirmed===dbConfirmed,expectedConfirmed,expectedCountMatch:dbConfirmed===expectedConfirmed};
}
