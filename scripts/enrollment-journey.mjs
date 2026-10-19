import { performance } from 'node:perf_hooks';

// Request injection keeps policy testable without a live database/server.
export async function observeJourney(base, studentId, unused, config, request) {
  const startedAt=Date.now(), start=performance.now(), steps=[], tried=new Set();
  const finish=outcome=>({studentId,startedAt,outcome,journeyMs:performance.now()-start,
    attempts:tried.size,steps,polls:0,pollErrors:0});
  const remaining=()=>config.deadlineMs-(performance.now()-start);
  for(let attempt=0;attempt<3;attempt++) {
    if(remaining()<=0)return finish('DEADLINE');
    let read, began;
    let cursor=0;
    const queries=config.searchMode?3:1;
    for(let page=0;page<queries;page++) {
      if(remaining()<=0)return finish('DEADLINE');
      const params=new URLSearchParams({year:'2026',semester:'2',department:config.department,size:config.searchMode?'50':'20'});
      if(config.searchMode){params.set('grade','1');if(page===1)params.set('afterId',String(cursor));if(page===2)params.set('keyword','수강실습');}
      began=performance.now();
      try {read=await request(`${base}/api/courses?${params}`,{},Math.max(1,remaining()));}
      catch(error){steps.push({kind:'read',page,elapsedMs:performance.now()-began,error:error.message});return finish('READ_FAILED');}
      steps.push({kind:'read',page,status:read.status,elapsedMs:performance.now()-began,timing:read.timing});
      if(read.status!==200||!Array.isArray(read.body?.items))return finish('READ_FAILED');
      cursor=read.body.nextAfterId??0;
    }
    const available=read.body.items.filter(c=>config.courseIds.includes(c.id)&&!tried.has(c.id)&&c.enrolledCount<c.capacity).sort((a,b)=>a.id-b.id);
    if(!available.length)return finish('NO_AVAILABLE_COURSE');
    // First preference is the same popular course. Alternatives are deterministic per student index.
    const course=attempt===0?available[0]:available[config.studentIndexes[studentId]%available.length];
    if(remaining()<=0)return finish('DEADLINE');
    tried.add(course.id);began=performance.now();
    let result;
    try {result=await request(`${base}/api/enrollments`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({studentId,courseId:course.id})},Math.max(1,remaining()));}
    catch(error){steps.push({kind:'submit',courseId:course.id,elapsedMs:performance.now()-began,error:error.message});return finish('SUBMIT_UNKNOWN');}
    steps.push({kind:'submit',courseId:course.id,status:result.status,code:result.body?.code,message:result.body?.message,timing:result.timing,elapsedMs:performance.now()-began});
    if(result.status===201)return finish('CONFIRMED');
    // Never retry ambiguous writes or infrastructure errors against a different course.
    if(result.status!==409||result.body?.code!=='COURSE_FULL')return finish('SUBMIT_FAILED');
  }
  return finish('ATTEMPT_LIMIT');
}
