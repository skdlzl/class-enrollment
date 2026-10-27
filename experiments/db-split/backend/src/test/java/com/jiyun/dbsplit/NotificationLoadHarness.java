package com.jiyun.dbsplit;

import com.jiyun.dbsplit.notification.*;
import com.zaxxer.hikari.HikariDataSource;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.nio.file.*;

/** Controlled arrival plan shared by both strategies. No automatic retry or load escalation. */
class NotificationLoadHarness {
 static int setting(String key,int fallback,int max){int n=Integer.getInteger("notification.load."+key,fallback);if(n<1||n>max)throw new IllegalArgumentException(key);return n;}
 static Map<String,Object> stats(Collection<Double> values){
  var sorted=new ArrayList<>(values);Collections.sort(sorted);var m=new LinkedHashMap<String,Object>();m.put("count",sorted.size());
  m.put("meanMs",sorted.stream().mapToDouble(x->x).average().orElse(0));
  m.put("p95Ms",sorted.isEmpty()?null:sorted.get((int)Math.ceil(sorted.size()*.95)-1));
  m.put("maxMs",sorted.isEmpty()?null:sorted.get(sorted.size()-1));return m;
 }
 static long count(Map<String,Object> snapshot,String phase){
  Object value=((Map<?,?>)snapshot.get("phases")).get(phase);
  return value==null?0:((Number)((Map<?,?>)value).get("count")).longValue();
 }
 static void save(Map<String,Object> report)throws Exception{
  var path=Path.of("target","notification-load.json");Files.createDirectories(path.getParent());
  new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(path.toFile(),report);
 }
 static void run(HikariDataSource source,String redisUrl,String run)throws Exception {
  int messages=setting("messages",1000,10000),rounds=setting("rounds",3,5),threads=setting("workers",8,64);
  int poolSize=setting("pool",10,32),delay=setting("delay-ms",100,1000),rps=setting("unique-rps",200,2000);
  if(messages<2)throw new IllegalArgumentException("Two-server load requires at least two messages");
  int duplicates=setting("duplicate-copies",2,4);
  var rows=new ArrayList<Map<String,Object>>();var report=new LinkedHashMap<String,Object>();
  report.put("scope","two independent notification HTTP server JVMs; shared MySQL and Redis; mock provider; not enrollment HTTP load");
  report.put("messagesPerRun",messages);report.put("rounds",rounds);report.put("servers",2);report.put("workersPerServer",threads);report.put("poolSizePerServer",poolSize);report.put("totalWorkers",threads*2);report.put("totalPoolSize",poolSize*2);
  report.put("providerLatencyMs",delay);report.put("uniqueArrivalRps",rps);report.put("duplicateCopies",duplicates);
  report.put("readTargetRps",50);report.put("results",rows);save(report);
  for(int round=1;round<=rounds;round++){
   String[] scenarios=round%2==1?new String[]{"UNIQUE","DUPLICATE"}:new String[]{"DUPLICATE","UNIQUE"};
   for(int scenarioIndex=0;scenarioIndex<2;scenarioIndex++)for(int offset=0;offset<2;offset++){
    var strategy=((round+offset)%2==1)?NotificationWorker.Strategy.DB_CLAIM:NotificationWorker.Strategy.REDIS_OUTBOX;
    String scenario=scenarios[scenarioIndex];
    var row=measure(source,redisUrl,run,round,scenario,strategy,messages,threads,poolSize,delay,rps,scenario.equals("UNIQUE")?1:duplicates);
    rows.add(row);save(report);
    if(!Boolean.TRUE.equals(row.get("accountingValid"))||!Boolean.TRUE.equals(row.get("integrityValid"))||((Number)row.get("processErrors")).intValue()>0||((Number)row.get("confirmedUnique")).intValue()!=messages)
     throw new AssertionError("Incomplete/error outcome preserved in target/notification-load.json; no further load");
   }
  }
 }
 static Map<String,Object> aggregate(List<Map<String,Object>> snapshots){
  var phases=new TreeMap<String,Map<String,Object>>();
  for(var snapshot:snapshots)((Map<?,?>)snapshot.get("phases")).forEach((key,value)->{
   var v=(Map<?,?>)value;var a=phases.computeIfAbsent(key.toString(),k->new LinkedHashMap<>(Map.of("count",0L,"failures",0L,"totalMs",0.0,"lifetimeMaxMs",0.0)));
   for(String k:List.of("count","failures"))a.put(k,((Number)a.get(k)).longValue()+((Number)v.get(k)).longValue());
   a.put("totalMs",((Number)a.get("totalMs")).doubleValue()+((Number)v.get("totalMs")).doubleValue());
   a.put("lifetimeMaxMs",Math.max(((Number)a.get("lifetimeMaxMs")).doubleValue(),((Number)v.get("lifetimeMaxMs")).doubleValue()));
  });
  return Map.of("phases",phases,"enabled",true);
 }
 static Map<String,Object> measure(HikariDataSource source,String redisUrl,String run,int round,String scenario,
  NotificationWorker.Strategy strategy,int messages,int threads,int poolSize,int delay,int rps,int copies)throws Exception{
  var directory=Path.of("target","notification-servers",run,round+"-"+scenario+"-"+strategy);
  try(var provider=new NotificationSafetyTest.Provider()){
   provider.delayMs=delay;
   try(var one=new NotificationServerProcess(source,redisUrl,provider.url(),strategy.name(),threads,poolSize,directory,1);
       var two=new NotificationServerProcess(source,redisUrl,provider.url(),strategy.name(),threads,poolSize,directory,2)){
   var nodes=List.of(one,two);if(one.pid==two.pid||one.pid==ProcessHandle.current().pid()||two.pid==ProcessHandle.current().pid())throw new AssertionError("Independent server JVMs required");
   var jdbc=new JdbcTemplate(source);String prefix=UUID.randomUUID().toString();var ids=new ArrayList<String>();
   for(int i=0;i<messages+20;i++){
    String id=UUID.randomUUID().toString();jdbc.update("INSERT INTO notification_outbox(id,request_id,run_id,event_type,payload) VALUES(?,?,?,'CONFIRMED','{}')",id,id,prefix);
    if(i<20){if(!nodes.get(i%2).process(id))throw new AssertionError("Warmup failed");}else ids.add(id);
   }
   provider.delivered.clear();provider.calls.set(0);
   for(var node:nodes)node.request(node.controlUrl+"/reset-peaks");
   var nodeBefore=List.of(one.snapshot(),two.snapshot());var before=aggregate(nodeBefore);
   var completed=new ConcurrentHashMap<String,Double>();var taskSamples=Collections.synchronizedList(new ArrayList<Map<String,Object>>());
   var reads=List.of(Collections.synchronizedList(new ArrayList<Double>()),Collections.synchronizedList(new ArrayList<Double>()));
   var readFailed=List.of(new AtomicInteger(),new AtomicInteger());var processErrors=new AtomicInteger();
   var senders=new ArrayList<ThreadPoolExecutor>();
   for(int node=0;node<2;node++){
    var sender=new ThreadPoolExecutor(threads,threads,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<Runnable>(messages*copies));
    sender.prestartAllCoreThreads();senders.add(sender);
   }
   var monitor=Executors.newScheduledThreadPool(2);var done=new CountDownLatch(messages*copies);
   long start=System.nanoTime();
   for(int n=0;n<2;n++){
    int node=n;monitor.scheduleWithFixedDelay(()->{
     long t=System.nanoTime();try{nodes.get(node).read();}catch(Exception e){readFailed.get(node).incrementAndGet();}
     reads.get(node).add((System.nanoTime()-t)/1e6);
    },0,20,TimeUnit.MILLISECONDS);
   }
   double maxSubmitLag=0;boolean finished=false;
   try{
    for(int i=0;i<messages;i++){
     String id=ids.get(i);long due=start+TimeUnit.SECONDS.toNanos(1)*i/rps;
     long remaining=due-System.nanoTime();if(remaining>0)TimeUnit.NANOSECONDS.sleep(remaining);
     maxSubmitLag=Math.max(maxSubmitLag,(System.nanoTime()-due)/1e6);
     for(int copy=0;copy<copies;copy++){
      int attempt=copy,node=(i+copy)%2;long queued=System.nanoTime();
      senders.get(node).execute(()->{
       long entered=System.nanoTime();String outcome="NOT_PROCESSED";
       try{if(nodes.get(node).process(id)){completed.putIfAbsent(id,(System.nanoTime()-due)/1e6);outcome="SENT";}}
       catch(Exception e){processErrors.incrementAndGet();outcome=e.getClass().getSimpleName();}
       finally{taskSamples.add(Map.of("id",id,"copy",attempt,"server",node+1,"queueWaitMs",(entered-queued)/1e6,
        "startLagMs",(entered-due)/1e6,"elapsedMs",(System.nanoTime()-entered)/1e6,"outcome",outcome));done.countDown();}
      });
     }
    }
    finished=done.await(180,TimeUnit.SECONDS);
   }finally{
    senders.forEach(ExecutorService::shutdownNow);monitor.shutdownNow();
    boolean stopped=monitor.awaitTermination(10,TimeUnit.SECONDS);
    for(var sender:senders)stopped=sender.awaitTermination(10,TimeUnit.SECONDS)&&stopped;
    if(!stopped)throw new IllegalStateException("Client load threads did not stop");
   }
   double wall=(System.nanoTime()-start)/1e6;var nodeAfter=List.of(one.snapshot(),two.snapshot());var after=aggregate(nodeAfter);
   int sent=jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE run_id=? AND status='SENT'",Integer.class,prefix)-20;
   var row=new LinkedHashMap<String,Object>();row.put("round",round);row.put("scenario",scenario);row.put("strategy",strategy.name());
   row.put("plannedUnique",messages);row.put("plannedAttempts",messages*copies);row.put("finished",finished);row.put("wallMs",wall);
   row.put("confirmedUnique",completed.size());row.put("dbSent",sent);row.put("providerUnique",provider.delivered.size());row.put("providerCalls",provider.calls.get());
   long processAttempts=count(after,"notification.processAttempt")-count(before,"notification.processAttempt");
   long claimUpdates=count(after,"notification.claimUpdateAttempt")-count(before,"notification.claimUpdateAttempt");
   long redisBusy=count(after,"notification.redisBusy")-count(before,"notification.redisBusy");
   var servers=new ArrayList<Map<String,Object>>();boolean bothServersValid=true;
   for(int n=0;n<2;n++){
    final int node=n;long expected=taskSamples.stream().filter(t->((Number)t.get("server")).intValue()==node+1).count();
    long actual=count(nodeAfter.get(n),"notification.processAttempt")-count(nodeBefore.get(n),"notification.processAttempt");
    bothServersValid=bothServersValid&&actual==expected&&actual>0;
    var server=new LinkedHashMap<String,Object>();server.put("server",n+1);server.put("pid",nodes.get(n).pid);server.put("processUrl",nodes.get(n).processUrl);
    server.put("expectedAttempts",expected);server.put("processAttempts",actual);server.put("metricsBefore",nodeBefore.get(n));server.put("metricsAfter",nodeAfter.get(n));
    server.put("reads",stats(reads.get(n)));server.put("readFailures",readFailed.get(n).get());server.put("actualReadRps",reads.get(n).size()*1000.0/wall);servers.add(server);
   }
   row.put("servers",servers);row.put("bothServersValid",bothServersValid);
   row.put("processAttempts",processAttempts);row.put("claimUpdateAttempts",claimUpdates);row.put("redisBusy",redisBusy);
   row.put("accountingValid",bothServersValid&&processAttempts==messages*copies&&claimUpdates+redisBusy==processAttempts);
   row.put("processErrors",processErrors.get());row.put("integrityValid",finished&&sent==completed.size()&&sent==provider.delivered.size());
   row.put("successRate",completed.size()/(double)messages);row.put("successfulCompletionFromScheduledArrival",stats(completed.values()));
   row.put("completedUniquePerSecond",completed.size()*1000.0/wall);row.put("generatorMaxSubmitLagMs",maxSubmitLag);
   var allReads=new ArrayList<Double>();reads.forEach(allReads::addAll);row.put("reads",stats(allReads));
   row.put("readFailures",readFailed.stream().mapToInt(AtomicInteger::get).sum());row.put("actualReadRps",allReads.size()*1000.0/wall);
   row.put("readModel","per-server closed-loop HTTP SELECT 1 with 20ms post-completion delay; not guaranteed 50RPS per server");
   row.put("metricsBefore",before);row.put("metricsAfter",after);row.put("tasks",taskSamples);return row;
   }
  }
 }
}
