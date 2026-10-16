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
