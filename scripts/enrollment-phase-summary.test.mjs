import test from 'node:test';
import assert from 'node:assert/strict';
import {summarizeEnrollmentPhases} from './enrollment-phase-summary.mjs';
const sample=(count,totalMs)=>({base:'server',status:200,body:{enrollmentTimings:{enabled:true,version:'v1',phases:{saveCall:{count,failures:0,totalMs,maxMs:900}}}}});
test('subtracts warmup counters but preserves lifetime max label',()=>{
 const [r]=summarizeEnrollmentPhases({before:[sample(10,1000)],after:[sample(12,1010)]});
 assert.equal(r.count,2);assert.equal(r.meanMs,5);assert.equal(r.lifetimeMaxMs,900);
});
test('reset and missing diagnostics are explicit',()=>{
 assert.ok(summarizeEnrollmentPhases({before:[sample(10,1000)],after:[sample(1,10)]})[0].error);
 assert.ok(summarizeEnrollmentPhases({before:[],after:[sample(1,10)]})[0].error);
});
