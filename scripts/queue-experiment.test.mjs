import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { observeRequest, readLoad, percentile } from './run-queue-experiment.mjs';
// Functional test timeout only; production measurement remains 45 seconds.
const config={deadlineMs:5000,httpTimeoutMs:2000,pollMs:10};
async function withServer(handler,run){
  const server=createServer(handler);await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  try{return await run(`http://127.0.0.1:${server.address().port}`);}finally{server.closeAllConnections();await new Promise(resolve=>server.close(resolve));}
}
function json(res,code,body){res.writeHead(code,{'Content-Type':'application/json'});res.end(JSON.stringify(body));}
test('P95 uses nearest rank and never invents a value for an empty group',()=>{
  assert.equal(percentile([]),null);assert.equal(percentile([1,2,3,4,5]),5);
});
test('202 is not completion; final observed time includes subsequent polling',async()=>{
  let polls=0;
  await withServer((req,res)=>req.method==='POST'?json(res,202,{requestId:'receipt',status:'QUEUED'}):json(res,200,{requestId:'receipt',status:++polls<2?'PROCESSING':'CONFIRMED'}),async base=>{
    const result=await observeRequest(base,1,1,config);
    assert.equal(result.outcome,'CONFIRMED');assert.equal(result.polls,2);
    assert.ok(result.observedResultMs>result.submitMs);
  });
});
test('business rejection is a final result, not a successful enrollment',async()=>{
  await withServer((req,res)=>json(res,req.method==='POST'?202:200,{requestId:'receipt',status:req.method==='POST'?'QUEUED':'REJECTED',message:'정원 마감'}),async base=>{
    const result=await observeRequest(base,1,1,config);assert.equal(result.outcome,'REJECTED');assert.equal(result.message,'정원 마감');
  });
});
test('unresolved remains unresolved and does not acquire a fabricated final latency',async()=>{
  await withServer((req,res)=>json(res,req.method==='POST'?202:200,{requestId:'receipt',status:'QUEUED'}),async base=>{
    const result=await observeRequest(base,1,1,{...config,deadlineMs:500});
    assert.equal(result.outcome,'UNRESOLVED');assert.equal(result.observedResultMs,undefined);
  });
});
test('non-202 submit and polling failures are counted separately',async()=>{
  await withServer((req,res)=>json(res,503,{}),async base=>{
    const result=await observeRequest(base,1,1,config);assert.equal(result.outcome,'SUBMIT_FAILED');assert.equal(result.pollErrors,0);
  });
  await withServer((req,res)=>req.method==='POST'?json(res,202,{requestId:'receipt',status:'QUEUED'}):json(res,503,{}),async base=>{
    const result=await observeRequest(base,1,1,{...config,deadlineMs:500});assert.equal(result.outcome,'UNRESOLVED');assert.ok(result.pollErrors>0);
  });
});
test('fixed read arrival schedule reports failures instead of excluding them from latency',async()=>{
  let count=0;
  await withServer((req,res)=>json(res,++count===1?503:200,{}),async base=>{
    const result=await readLoad(base,{readSeconds:1,readRps:4,httpTimeoutMs:2000});
    assert.equal(result.count,4);assert.equal(result.failed,1);assert.equal(result.actualStartRps,4);assert.equal(result.skipped,0);
  });
});

test('synchronous comparison counts 201 and business 409 as final, never 503',async()=>{
  const {observeSynchronous}=await import('./run-queue-experiment.mjs');
  for(const [code,body,outcome] of [[201,{},'CONFIRMED'],[409,{code:'COURSE_FULL'},'REJECTED'],[503,{},'SUBMIT_FAILED'],[409,{},'SUBMIT_FAILED']]){
    await withServer((req,res)=>json(res,code,body),async base=>{
      const result=await observeSynchronous(base,1,1,config);
      assert.equal(result.outcome,outcome);assert.equal(result.polls,0);
      assert.equal(Number.isFinite(result.observedResultMs),['CONFIRMED','REJECTED'].includes(outcome));
    });
  }
});
test('synchronous network failure is unknown rather than business rejection',async()=>{
  const {observeSynchronous}=await import('./run-queue-experiment.mjs');
  await withServer((req,res)=>req.socket.destroy(),async base=>{
    const result=await observeSynchronous(base,1,1,config);assert.equal(result.outcome,'SUBMIT_UNKNOWN');
  });
});

import { ExperimentHttp } from './experiment-http.mjs';
test('bounded submit pool queues requests, reuses sockets and does not block catalog pool',async()=>{
  const client=new ExperimentHttp({submit:1,catalog:1,poll:1,diagnostic:1});
  let release,entered;
  const arrived=new Promise(r=>entered=r);
  let posts=0;
  try{await withServer((req,res)=>{
    if(req.method==='POST'&&++posts===1){release=()=>json(res,202,{});entered();}
    else json(res,req.method==='POST'?202:200,{});
  },async base=>{
    const first=client.request(base+'/api/enrollment-requests',{method:'POST'},2000);
    await arrived;
    const second=client.request(base+'/api/enrollment-requests',{method:'POST'},2000);
    const read=await client.request(base+'/api/courses',{},1000);
    assert.equal(read.status,200);assert.equal(posts,1);
    await new Promise(r=>setTimeout(r,30));release();
    const [a,b]=await Promise.all([first,second]);
    assert.equal(a.status,202);assert.equal(b.status,202);
    assert.ok(b.timing.socketAssignedMs>=25);assert.equal(b.timing.reusedSocket,true);
  });}finally{client.close();}
});
test('HTTP deadline includes waiting for a pooled socket',async()=>{
  const client=new ExperimentHttp({submit:1,catalog:1,poll:1,diagnostic:1});
  let release,entered;
  const arrived=new Promise(r=>entered=r);
  try{await withServer((req,res)=>{release=()=>json(res,202,{});entered();},async base=>{
    const first=client.request(base+'/api/enrollment-requests',{method:'POST'},2000);
    await arrived;
    await assert.rejects(client.request(base+'/api/enrollment-requests',{method:'POST'},30),{code:'HTTP_DEADLINE'});
    const timed=client.drain()[0];assert.equal(timed.socketAssignedMs,null);
    assert.ok(timed.elapsedMs>=25);release();await first;
  });}finally{client.close();}
});

import { validateSyncLockMode } from './sync-lock-modes.mjs';
test('synchronous lock comparison rejects queue, gate, precheck and strategy mismatches',()=>{
  const redis={strategy:'REDIS',gate:false,precheck:false,max:10,queueEnabled:false};
  assert.doesNotThrow(()=>validateSyncLockMode('sync-redis',redis));
  assert.doesNotThrow(()=>validateSyncLockMode('sync-pessimistic',{...redis,strategy:'PESSIMISTIC'}));
  for(const [key,value] of Object.entries({queueEnabled:true,gate:true,precheck:true,max:50,strategy:'PESSIMISTIC'}))
    assert.throws(()=>validateSyncLockMode('sync-redis',{...redis,[key]:value}));
});


test('both lock modes use synchronous enrollment endpoint without polling',async()=>{
  const {phase}=await import('./run-queue-experiment.mjs');
  for(const mode of ['sync-redis','sync-pessimistic','sync-redis-precheck','sync-pessimistic-precheck']){
    const paths=[];
    await withServer((req,res)=>{paths.push([req.method,req.url]);json(res,201,{});},async base=>{
      const result=await phase('test',[base],[1],1,{...config,mode,readSeconds:0,readRps:1,
        collectPool:false,enrollmentDelayMs:0,rampSeconds:0.01});
      assert.deepEqual(paths,[['POST','/api/enrollments']]);
      assert.equal(result.enrollments[0].outcome,'CONFIRMED');
      assert.equal(result.enrollments[0].polls,0);
    });
  }
});


test('precheck modes require precheck on for both strategies and keep gate off',()=>{
  for(const strategy of ['REDIS','PESSIMISTIC']){
    const mode=`sync-${strategy.toLowerCase()}-precheck`;
    const state={strategy,gate:false,precheck:true,max:10,queueEnabled:false};
    assert.doesNotThrow(()=>validateSyncLockMode(mode,state));
    assert.throws(()=>validateSyncLockMode(mode,{...state,precheck:false}));
    assert.throws(()=>validateSyncLockMode(mode,{...state,gate:true}));
    assert.throws(()=>validateSyncLockMode(mode,{...state,queueEnabled:true}));
  }
});
