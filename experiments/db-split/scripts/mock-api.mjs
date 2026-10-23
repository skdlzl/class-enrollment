// CI transport smoke only. Never used by the performance runner.
import {createServer} from 'node:http';
for(const port of [28080,28081])createServer(async(req,res)=>{
 let body='';for await(const chunk of req)body+=chunk;
 const url=new URL(req.url,'http://localhost');res.setHeader('content-type','application/json');
 if(req.method==='POST') {res.statusCode=201;res.end(JSON.stringify({requestId:JSON.parse(body).requestId,status:'CONFIRMED'}));}
 else res.end(JSON.stringify([{id:1,name:'수강실습',enrolledCount:0,capacity:100}]));
}).listen(port,'127.0.0.1');
