import test from 'node:test';
import assert from 'node:assert/strict';
import { observeJourney } from './enrollment-journey.mjs';
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
