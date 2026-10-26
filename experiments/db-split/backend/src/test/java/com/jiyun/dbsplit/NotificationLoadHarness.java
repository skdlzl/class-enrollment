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
 static void run(HikariDataSource source,RedissonClient redis,String run)throws Exception {
  int messages=setting("messages",1000,10000),rounds=setting("rounds",3,5),threads=setting("workers",16,64);
  int poolSize=setting("pool",10,32),delay=setting("delay-ms",100,1000),rps=setting("unique-rps",200,2000);
  int duplicates=setting("duplicate-copies",2,4);
  var rows=new ArrayList<Map<String,Object>>();var report=new LinkedHashMap<String,Object>();
  report.put("scope","controlled notification load; mock provider; not actual enrollment load or measured production duplicate rate");
  report.put("messagesPerRun",messages);report.put("rounds",rounds);report.put("workers",threads);report.put("poolSize",poolSize);
  report.put("providerLatencyMs",delay);report.put("uniqueArrivalRps",rps);report.put("duplicateCopies",duplicates);
  report.put("readTargetRps",50);report.put("results",rows);save(report);
  for(int round=1;round<=rounds;round++){
   String[] scenarios=round%2==1?new String[]{"UNIQUE","DUPLICATE"}:new String[]{"DUPLICATE","UNIQUE"};
   for(int scenarioIndex=0;scenarioIndex<2;scenarioIndex++)for(int offset=0;offset<2;offset++){
    var strategy=((round+offset)%2==1)?NotificationWorker.Strategy.DB_CLAIM:NotificationWorker.Strategy.REDIS_OUTBOX;
    String scenario=scenarios[scenarioIndex];
    var row=measure(source,redis,run,round,scenario,strategy,messages,threads,poolSize,delay,rps,scenario.equals("UNIQUE")?1:duplicates);
    rows.add(row);save(report);
    if(!Boolean.TRUE.equals(row.get("accountingValid"))||!Boolean.TRUE.equals(row.get("integrityValid"))||((Number)row.get("processErrors")).intValue()>0||((Number)row.get("confirmedUnique")).intValue()!=messages)
     throw new AssertionError("Incomplete/error outcome preserved in target/notification-load.json; no further load");
   }
  }
 }
 static Map<String,Object> measure(HikariDataSource source,RedissonClient redis,String run,int round,String scenario,
  NotificationWorker.Strategy strategy,int messages,int threads,int poolSize,int delay,int rps,int copies)throws Exception{
  try(var provider=new NotificationSafetyTest.Provider();var pool=new HikariDataSource()){
   provider.delayMs=delay;pool.setJdbcUrl(source.getJdbcUrl());pool.setUsername(source.getUsername());pool.setPassword(source.getPassword());
   pool.setMaximumPoolSize(poolSize);pool.setMinimumIdle(poolSize);pool.setConnectionTimeout(5000);pool.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
   pool.setPoolName("loadPool");var metrics=new Metrics(true);metrics.postProcessBeforeInitialization(pool,"loadPool");
   var jdbc=new JdbcTemplate(pool);var store=new NotificationStore(jdbc);var beans=new StaticListableBeanFactory();beans.addBean("redis",redis);
   var worker=new NotificationWorker(store,new NotificationGateway(provider.url(),3000),beans.getBeanProvider(RedissonClient.class),metrics,
    new DataSourceTransactionManager(pool),true,false,strategy,30000,5);
   String prefix=UUID.randomUUID().toString();var ids=new ArrayList<String>();
   for(int i=0;i<messages+10;i++){
    String id=UUID.randomUUID().toString();jdbc.update("INSERT INTO notification_outbox(id,request_id,run_id,event_type,payload) VALUES(?,?,?,'CONFIRMED','{}')",id,id,prefix);
    if(i<10){if(!worker.process(id))throw new AssertionError("Warmup failed");}else ids.add(id);
   }
   provider.delivered.clear();provider.calls.set(0);
   var before=metrics.snapshot();var completed=new ConcurrentHashMap<String,Double>();
   var taskSamples=Collections.synchronizedList(new ArrayList<Map<String,Object>>());
   var readSamples=Collections.synchronizedList(new ArrayList<Double>());var readFailed=new AtomicInteger();
   var processErrors=new AtomicInteger();var running=new AtomicBoolean(true);var peakWaiting=new AtomicInteger();var peakActive=new AtomicInteger();
   var senders=new ThreadPoolExecutor(threads,threads,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<Runnable>(messages*copies));
   senders.prestartAllCoreThreads();var monitor=Executors.newScheduledThreadPool(2);
   var done=new CountDownLatch(messages*copies);long start=System.nanoTime();
   monitor.scheduleWithFixedDelay(()->{
    if(!running.get())return;long t=System.nanoTime();
    try{jdbc.queryForObject("SELECT 1",Integer.class);}catch(RuntimeException e){readFailed.incrementAndGet();}
    readSamples.add((System.nanoTime()-t)/1e6);
   },0,20,TimeUnit.MILLISECONDS);
   monitor.scheduleWithFixedDelay(()->{
    peakWaiting.accumulateAndGet(pool.getHikariPoolMXBean().getThreadsAwaitingConnection(),Math::max);
    peakActive.accumulateAndGet(pool.getHikariPoolMXBean().getActiveConnections(),Math::max);
   },0,10,TimeUnit.MILLISECONDS);
   double maxSubmitLag=0;boolean finished=false;
   try{
    for(int i=0;i<messages;i++){
     String id=ids.get(i);long due=start+TimeUnit.SECONDS.toNanos(1)*i/rps;
     long remaining=due-System.nanoTime();if(remaining>0)TimeUnit.NANOSECONDS.sleep(remaining);
     maxSubmitLag=Math.max(maxSubmitLag,(System.nanoTime()-due)/1e6);
     for(int copy=0;copy<copies;copy++){
      int attempt=copy;long queued=System.nanoTime();
      senders.execute(()->{
       long entered=System.nanoTime();String outcome="NOT_PROCESSED";
       try{if(worker.process(id)){completed.putIfAbsent(id,(System.nanoTime()-due)/1e6);outcome="SENT";}}
       catch(RuntimeException e){processErrors.incrementAndGet();outcome=e.getClass().getSimpleName();}
       finally{
        taskSamples.add(Map.of("id",id,"copy",attempt,"queueWaitMs",(entered-queued)/1e6,
         "startLagMs",(entered-due)/1e6,"elapsedMs",(System.nanoTime()-entered)/1e6,"outcome",outcome));done.countDown();
       }
      });
     }
    }
    finished=done.await(180,TimeUnit.SECONDS);
   }finally{
    running.set(false);senders.shutdownNow();monitor.shutdownNow();
    if(!senders.awaitTermination(10,TimeUnit.SECONDS)||!monitor.awaitTermination(10,TimeUnit.SECONDS))
     throw new IllegalStateException("Load threads did not stop; incomplete run must not be interpreted");
   }
   double wall=(System.nanoTime()-start)/1e6;
   var after=metrics.snapshot();
   int sent=jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE run_id=? AND status='SENT'",Integer.class,prefix)-10;
   var row=new LinkedHashMap<String,Object>();row.put("round",round);row.put("scenario",scenario);row.put("strategy",strategy.name());
   row.put("plannedUnique",messages);row.put("plannedAttempts",messages*copies);row.put("finished",finished);row.put("wallMs",wall);
   row.put("confirmedUnique",completed.size());row.put("dbSent",sent);row.put("providerUnique",provider.delivered.size());row.put("providerCalls",provider.calls.get());
   long processAttempts=count(after,"notification.processAttempt")-count(before,"notification.processAttempt");
   long claimUpdates=count(after,"notification.claimUpdateAttempt")-count(before,"notification.claimUpdateAttempt");
   long redisBusy=count(after,"notification.redisBusy")-count(before,"notification.redisBusy");
   row.put("processAttempts",processAttempts);row.put("claimUpdateAttempts",claimUpdates);row.put("redisBusy",redisBusy);
   row.put("accountingValid",processAttempts==messages*copies&&claimUpdates+redisBusy==processAttempts);
   row.put("processErrors",processErrors.get());row.put("integrityValid",finished&&sent==completed.size()&&sent==provider.delivered.size());
   row.put("successRate",completed.size()/(double)messages);row.put("successfulCompletionFromScheduledArrival",stats(completed.values()));
   row.put("completedUniquePerSecond",completed.size()*1000.0/wall);row.put("generatorMaxSubmitLagMs",maxSubmitLag);
   row.put("reads",stats(readSamples));row.put("readFailures",readFailed.get());row.put("actualReadRps",readSamples.size()*1000.0/wall);
   row.put("readModel","closed-loop SELECT 1 with 20ms post-completion delay; not guaranteed 50RPS");
   row.put("peakPoolWaiting",peakWaiting.get());row.put("peakPoolActive",peakActive.get());row.put("metricsBefore",before);row.put("metricsAfter",after);
   row.put("tasks",taskSamples);return row;
  }
 }
}
