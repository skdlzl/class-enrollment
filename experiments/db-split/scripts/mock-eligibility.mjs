import http from 'node:http';
import fs from 'node:fs';
const [readyFile, delayArg = '100'] = process.argv.slice(2);
const delayMs = Number(delayArg);
if (!readyFile || !Number.isInteger(delayMs) || delayMs < 0 || delayMs > 1000) throw new Error('ready file and delay 0..1000 required');
let calls = 0, active = 0, maxActive = 0;
const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');
  res.setHeader('content-type', 'application/json');
  if (url.pathname === '/state') {res.end(JSON.stringify({pid: process.pid, delayMs, calls, active, maxActive, readOnly: true})); return;}
  if (url.pathname !== '/eligibility' || req.method !== 'GET') {res.writeHead(404);res.end();return;}
  if (!/^\d+$/.test(url.searchParams.get('studentId') ?? '') || !url.searchParams.get('requestId')) {res.writeHead(400);res.end();return;}
  calls++;active++;maxActive=Math.max(maxActive,active);
  setTimeout(() => {active--;res.end(JSON.stringify({eligible:true}));},delayMs);
});
server.listen(0,'127.0.0.1',()=>{
  const ready={pid:process.pid,port:server.address().port,delayMs};
  fs.writeFileSync(readyFile+'.tmp',JSON.stringify(ready));fs.renameSync(readyFile+'.tmp',readyFile);
});
