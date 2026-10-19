import test from 'node:test';
import assert from 'node:assert/strict';
import { observeJourney, assertSearchTargets } from './enrollment-journey.mjs';
const config={deadlineMs:1000,department:'TEST',courseIds:[1,2,3,4,5],studentIndexes:{7:0}};
const page={status:200,body:{items:[1,2,3,4,5].map(id=>({id,capacity:100,enrolledCount:0}))}};
test('fresh query before each alternative; stop on first success',async()=>{
  const calls=[];let submits=0;
  const result=await observeJourney('http://test',7,null,config,async(url,init)=>{
    calls.push(init.method??'GET');
    if(init.method!=='POST')return page;
    const course=JSON.parse(init.body).courseId;
    assert.equal(course,++submits);
    return submits===1?{status:409,body:{code:'COURSE_FULL'}}:{status:201};
  });
  assert.equal(result.outcome,'CONFIRMED');assert.deepEqual(calls,['GET','POST','GET','POST']);
});
test('ambiguous submission is never retried',async()=>{
  let calls=0;const r=await observeJourney('http://test',7,null,config,async(u,i)=>{calls++;if(i.method==='POST')throw Error('timeout');return page;});
  assert.equal(r.outcome,'SUBMIT_UNKNOWN');assert.equal(calls,2);
});
test('at most three writes even with stale availability',async()=>{
  const r=await observeJourney('http://test',7,null,config,async(u,i)=>i.method==='POST'?{status:409,body:{code:'COURSE_FULL'}}:page);
  assert.equal(r.outcome,'ATTEMPT_LIMIT');assert.equal(r.attempts,3);
});
test('full query result avoids POST; unrelated 409 stops',async()=>{
  const r=await observeJourney('http://test',7,null,config,async()=>({status:200,body:{items:[]}}));assert.equal(r.outcome,'NO_AVAILABLE_COURSE');assert.equal(r.attempts,0);
  const other=await observeJourney('http://test',7,null,config,async(u,i)=>i.method==='POST'?{status:409,body:{code:'CREDIT_LIMIT_EXCEEDED'}}:page);
  assert.equal(other.outcome,'SUBMIT_FAILED');assert.equal(other.attempts,1);
});
test('retain lock error message and per-request transport timing',async()=>{
  const timing={socketAssignedMs:12,elapsedMs:45};
  const r=await observeJourney('http://test',7,null,config,async(u,i)=>i.method==='POST'?{status:503,body:{code:'LOCK_ACQUISITION_TIMEOUT',message:'course lock timeout'},timing}:{...page,timing});
  assert.equal(r.outcome,'SUBMIT_FAILED');assert.equal(r.steps[1].message,'course lock timeout');assert.deepEqual(r.steps[1].timing,timing);assert.deepEqual(r.steps[0].timing,timing);
});
test('search journey uses actual pagination then filtered results before submission',async()=>{
  const calls=[];
  const r=await observeJourney('http://test',7,null,{...config,searchMode:true},async(url,init)=>{
    if(init.method==='POST'){calls.push('POST');assert.equal(JSON.parse(init.body).courseId,1);return {status:201};}
    const params=new URL(url).searchParams;calls.push(params);
    if(params.get('keyword'))return page;
    return {status:200,body:{items:[{id:999,capacity:100,enrolledCount:0}],nextAfterId:999}};
  });
  assert.equal(r.outcome,'CONFIRMED');assert.equal(calls.length,4);
  assert.equal(calls[0].get('grade'),'1');assert.equal(calls[0].get('size'),'50');
  assert.equal(calls[1].get('afterId'),'999');assert.equal(calls[2].get('keyword'),'수강실습');
});

test('preflight refuses empty or corrupted keyword results before load begins',()=>{
  assert.throws(()=>assertSearchTargets({status:200,body:{items:[]}},[1]),/검색 사전 검증 실패/);
  const item={id:1,grade:1,capacity:100,enrolledCount:0,name:'수강실습 C1'};
  assert.doesNotThrow(()=>assertSearchTargets({status:200,body:{items:[item]}},[1]));
  assert.throws(()=>assertSearchTargets({status:200,body:{items:[{...item,name:'???'}]}},[1]));
  assert.throws(()=>assertSearchTargets({status:200,body:{items:[item]}},[1,2]));
});
