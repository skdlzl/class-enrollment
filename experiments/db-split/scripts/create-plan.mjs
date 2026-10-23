import { writeFileSync } from 'node:fs';
import { resolve,dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
const esc=s=>String(s).replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;').replaceAll('"','&quot;');
const prop=(k,v)=>`<stringProp name="${k}">${esc(v)}</stringProp>`;
const bool=(k,v)=>`<boolProp name="${k}">${v}</boolProp>`;
const script=resolve(dirname(fileURLToPath(import.meta.url)),'request.groovy');
function group(name,kind,port,journey=false,smoke=false){
 const sampler=`<JSR223Sampler guiclass="TestBeanGUI" testclass="JSR223Sampler" testname="${name}" enabled="true">${prop('scriptLanguage','groovy')}${prop('filename',script)}${prop('cacheKey','true')}${prop('parameters',`${kind} http://127.0.0.1:${port}`)}${prop('script','')}</JSR223Sampler><hashTree/>`;
 const timer=journey?'':`<ConstantThroughputTimer guiclass="TestBeanGUI" testclass="ConstantThroughputTimer" testname="Target read RPS"><doubleProp><name>throughput</name><value>${smoke?120:1500}</value><savedValue>0.0</savedValue></doubleProp><intProp name="calcMode">2</intProp></ConstantThroughputTimer><hashTree/>`;
 // calcMode 2 = all active threads in this thread group. Four separate read streams.
 return `<ThreadGroup guiclass="ThreadGroupGui" testclass="ThreadGroup" testname="${name}" enabled="true">${prop('ThreadGroup.on_sample_error','continue')}<elementProp name="ThreadGroup.main_controller" elementType="LoopController" guiclass="LoopControlPanel" testclass="LoopController">${bool('LoopController.continue_forever',false)}${prop('LoopController.loops',journey?'1':'-1')}</elementProp>${prop('ThreadGroup.num_threads',smoke?'2':(journey?'500':'25'))}${prop('ThreadGroup.ramp_time',smoke?'1':(journey?'5':'1'))}${bool('ThreadGroup.scheduler',true)}${prop('ThreadGroup.duration',smoke?'8':(journey?'80':'90'))}${prop('ThreadGroup.delay',journey?(smoke?'1':'5'):'0')}</ThreadGroup><hashTree>${timer}${sampler}</hashTree>`;
}
export function plan(mixed,smoke=false){return `<?xml version="1.0" encoding="UTF-8"?><jmeterTestPlan version="1.2" properties="5.0" jmeter="5.6.3"><hashTree><TestPlan guiclass="TestPlanGui" testclass="TestPlan" testname="DB split stage A">${bool('TestPlan.functional_mode',false)}${bool('TestPlan.serialize_threadgroups',false)}</TestPlan><hashTree>${[28080,28081].flatMap(p=>['academic','offering'].map(k=>group(`${k}-${p}`,k,p,false,smoke))).join('')}${mixed?group('journey','journey',28080,true,smoke):''}</hashTree></hashTree></jmeterTestPlan>`;}
if(process.argv[2])writeFileSync(process.argv[2],plan(process.argv[3]==='mixed',process.argv.includes('--smoke')));
