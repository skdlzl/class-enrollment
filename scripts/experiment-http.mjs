import http from 'node:http';
import https from 'node:https';
import { performance } from 'node:perf_hooks';

// Separate bounded keep-alive pools; maxSockets is per origin, not a global limit.
export class ExperimentHttp {
  constructor(limits={submit:128,catalog:16,poll:32,diagnostic:2}) {
    this.limits=limits;this.agents={};this.samples=[];this.seenSockets=new WeakSet();
    for(const [group,maxSockets] of Object.entries(limits))this.agents[group]={
      'http:':new http.Agent({keepAlive:true,maxSockets,maxFreeSockets:maxSockets}),
      'https:':new https.Agent({keepAlive:true,maxSockets,maxFreeSockets:maxSockets})};
  }
  group(url,method){
    if(method==='POST')return 'submit';
    if(url.pathname==='/api/courses')return 'catalog';
    if(url.pathname.startsWith('/api/experiment/')||url.pathname.endsWith('/health'))return 'diagnostic';
    return 'poll';
  }
  drain(){const samples=this.samples;this.samples=[];return samples;}
  close(){for(const groups of Object.values(this.agents))for(const agent of Object.values(groups))agent.destroy();}
  request(address,init={},timeout=10000){
    const url=new URL(address),method=init.method??'GET',group=this.group(url,method),start=performance.now();
    const sample={group,origin:url.origin,startedAt:Date.now(),socketAssignedMs:null,connectedMs:null,
      requestFlushedMs:null,headersMs:null,reusedSocket:false};
    return new Promise((resolve,reject)=>{
      let settled=false,timer;
      const finish=(error,value)=>{
        if(settled)return;settled=true;clearTimeout(timer);
        sample.elapsedMs=performance.now()-start;
        if(error){sample.error=error.message;sample.errorCode=error.code??error.name;}
        this.samples.push(sample);
        error?reject(error):resolve({...value,timing:sample});
      };
      const req=(url.protocol==='https:'?https:http).request(url,{method,headers:init.headers,agent:this.agents[group][url.protocol]},res=>{
        sample.headersMs=performance.now()-start;sample.status=res.statusCode;
        const chunks=[];
        res.on('data',chunk=>chunks.push(chunk));
        res.on('error',error=>finish(error));
        res.on('aborted',()=>finish(new Error('Response aborted')));
        res.on('end',()=>{
          let body=null;try{body=JSON.parse(Buffer.concat(chunks).toString('utf8'));}catch{}
          finish(null,{status:res.statusCode,body});
        });
      });
      req.on('socket',socket=>{
        sample.socketAssignedMs=performance.now()-start;sample.reusedSocket=this.seenSockets.has(socket);this.seenSockets.add(socket);
        if(!socket.connecting)sample.connectedMs=sample.socketAssignedMs;
        else socket.once(url.protocol==='https:'?'secureConnect':'connect',()=>{sample.connectedMs=performance.now()-start;});
      });
      req.on('finish',()=>{sample.requestFlushedMs=performance.now()-start;});
      req.on('error',error=>finish(error));
      // Includes Agent queue, connection establishment and response body, not just socket idle time.
      timer=setTimeout(()=>{
        const error=Object.assign(new Error('HTTP deadline exceeded'),{code:'HTTP_DEADLINE'});
        req.destroy(error);finish(error);
      },timeout);
      req.end(init.body);
    });
  }
}
