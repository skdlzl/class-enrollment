import test from 'node:test';
import assert from 'node:assert/strict';
import {observeStudentGroup,assessIntegrity} from './student-contention.mjs';
const bases=['http://localhost:8080','http://localhost:8081'];
const group={index:0,department:'D',courseIds:[11,12,13,14,15],studentIds:[1,1,1,1,1]};
const catalog={status:200,body:{items:group.courseIds.map(id=>({id,enrolledCount:0})),nextAfterId:20}};

test('five submissions overlap, cross JVMs, and only follow three successful reads',async()=>{
  let reads=0,posts=[];const releases=[];
  const request=async(url,init={})=>{
    if(!init.method){reads++;return catalog;}
    assert.equal(reads,3);posts.push({url,body:JSON.parse(init.body)});
    return new Promise(resolve=>{releases.push(resolve);if(releases.length===5)releases.forEach(r=>r({status:201}));});
  };
  const result=await observeStudentGroup(group,bases,request,'valid');
  assert.equal(posts.length,5);assert.equal(new Set(posts.map(p=>new URL(p.url).origin)).size,2);
  assert.deepEqual(posts.map(p=>p.body.studentId),[1,1,1,1,1]);
  assert.equal(result.requests.filter(r=>r.outcome==='CONFIRMED').length,5);
});
test('control assigns distinct students while retaining the same courses',async()=>{
  const seen=[];
  const r=await observeStudentGroup({...group,studentIds:[1,2,3,4,5]},bases,async(url,init={})=>{
    if(!init.method)return catalog;seen.push(JSON.parse(init.body));return {status:201};
  },'valid');
  assert.equal(r.requests.length,5);assert.equal(new Set(seen.map(s=>s.studentId)).size,5);
  assert.deepEqual(seen.map(s=>s.courseId),group.courseIds);
});
test('unavailable search targets abort before any writes',async()=>{
  let posts=0;const r=await observeStudentGroup(group,bases,async(_url,init={})=>{
    if(init.method)posts++;return {status:200,body:{items:[]}};
  },'valid');
  assert.equal(r.outcome,'READ_FAILED');assert.equal(posts,0);
});
test('expected credit rejection is separate from timeout and no request is retried',async()=>{
  let n=0;const r=await observeStudentGroup(group,bases,async(_url,init={})=>{
    if(!init.method)return catalog;
    n++;return n<=3?{status:201}:n===4?{status:409,body:{code:'CREDIT_LIMIT_EXCEEDED'}}:{status:503,body:{code:'LOCK_ACQUISITION_TIMEOUT'}};
  },'credit');
  assert.equal(n,5);assert.deepEqual(r.requests.map(q=>q.outcome),['CONFIRMED','CONFIRMED','CONFIRMED','EXPECTED_REJECTION','SUBMIT_FAILED']);
});
test('lost responses remain unknown instead of being retried',async()=>{
  let n=0;const r=await observeStudentGroup(group,bases,async(_url,init={})=>{
    if(!init.method)return catalog;n++;throw Error('Lost response');
  },'valid');
  assert.equal(n,5);assert.ok(r.requests.every(q=>q.outcome==='SUBMIT_UNKNOWN'));
});
test('integrity assessment rejects credit overflow and schedule conflicts even with matching counters',()=>{
  const db=[{capacity:100,enrolled:4,actual:4}];
  const r=assessIntegrity(db,[{maxCredits:9,credits:12}],1,0,4,4);
  assert.equal(r.databaseConsistent,false);assert.equal(r.creditSafe,false);assert.equal(r.scheduleSafe,false);
});
test('integrity assessment checks expected completions and client/DB agreement separately',()=>{
  const r=assessIntegrity([{capacity:100,enrolled:3,actual:3}],[{maxCredits:9,credits:9}],0,0,2,3);
  assert.equal(r.databaseConsistent,true);assert.equal(r.expectedCountMatch,true);assert.equal(r.clientDatabaseMatch,false);
});
