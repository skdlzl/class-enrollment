package com.jiyun.dbsplit;

import com.jiyun.dbsplit.notification.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.*;
import org.testcontainers.junit.jupiter.Container;
import org.redisson.api.RedissonClient;
import com.zaxxer.hikari.HikariDataSource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@TestPropertySource(properties={"experiment.mode=B","experiment.notifications.enabled=true",
 "experiment.notifications.auto=false","experiment.notifications.strategy=REDIS_OUTBOX"})
class NotificationSafetyTest extends EnrollmentSafetyTest {
 @Container static final MySQLContainer<?> academicMysql=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> redisContainer=new GenericContainer<>("redis:7.4.2").withExposedPorts(6379);
 @DynamicPropertySource static void extra(DynamicPropertyRegistry r){
  r.add("experiment.academic.url",academicMysql::getJdbcUrl);r.add("experiment.academic.username",academicMysql::getUsername);
  r.add("experiment.academic.password",academicMysql::getPassword);
  r.add("experiment.redis.address",()->"redis://"+redisContainer.getHost()+":"+redisContainer.getMappedPort(6379));
 }
 @Autowired NotificationStore store;@Autowired RedissonClient redis;@Autowired HikariDataSource pool;
 static class Provider implements AutoCloseable {
  final HttpServer server;final ExecutorService executor=Executors.newCachedThreadPool();
  final Map<String,String> delivered=new ConcurrentHashMap<>();final AtomicInteger calls=new AtomicInteger();
  volatile long delayMs;volatile boolean loseResponse;volatile int status=200;
  volatile CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(0);
  Provider() throws Exception {
   server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(executor);
   server.createContext("/send",exchange->{
    calls.incrementAndGet();String key=exchange.getRequestHeaders().getFirst("Idempotency-Key");
    String payload=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
    int code=status;
    if(key==null){code=400;}else if(code==200){
     String existing=delivered.putIfAbsent(key,payload);
     if(existing!=null&&!existing.equals(payload))code=409;
    }
    entered.countDown();
    try{
     release.await(5,TimeUnit.SECONDS);if(delayMs>0)Thread.sleep(delayMs);
     if(loseResponse){loseResponse=false;exchange.close();return;}
     exchange.sendResponseHeaders(code,-1);exchange.close();
    }catch(InterruptedException e){Thread.currentThread().interrupt();exchange.close();}
   });server.start();
  }
  String url(){return "http://127.0.0.1:"+server.getAddress().getPort()+"/send";}
  public void close(){release.countDown();server.stop(0);executor.shutdownNow();}
 }
 NotificationWorker worker(Provider provider,long leaseMs){
  var beans=new StaticListableBeanFactory();beans.addBean("redis",redis);
  return new NotificationWorker(store,new NotificationGateway(provider.url(),5000),beans.getBeanProvider(RedissonClient.class),
   metrics,tx,true,false,NotificationWorker.Strategy.REDIS_OUTBOX,leaseMs,5);
 }
 String event(EnrollmentService.Request r){return jdbc.queryForObject("SELECT id FROM notification_outbox WHERE request_id=? AND event_type='CONFIRMED'",String.class,r.requestId());}
 void due(String id){jdbc.update("UPDATE notification_outbox SET next_attempt=CURRENT_TIMESTAMP(6),lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",id);}
 String status(String id){return jdbc.queryForObject("SELECT status FROM notification_outbox WHERE id=?",String.class,id);}
 @Test void outboxRollsBackWithEnrollmentAndRetryCreatesOneEvent(){
  var r=request(1,1);
  assertThrows(IllegalStateException.class,()->new TransactionTemplate(tx).execute(s->{service.enroll(r);throw new IllegalStateException();}));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE request_id=?",Integer.class,r.requestId()));
  service.enroll(r);service.enroll(r);service.cancel(r);service.cancel(r);
  assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE request_id=?",Integer.class,r.requestId()));consistent();
 }
 @Test void externalCallUsesNoDbConnectionButInlineComparatorDoes() throws Exception {
  for(var strategy:NotificationWorker.Strategy.values()){
   var r=request(strategy.ordinal()+1,strategy.ordinal()+1);service.enroll(r);String id=event(r);
   try(var provider=new Provider()){
    provider.release=new CountDownLatch(1);var worker=worker(provider,30000);var executor=Executors.newSingleThreadExecutor();
    try{
     var result=executor.submit(()->worker.process(id,strategy));
     assertTrue(provider.entered.await(5,TimeUnit.SECONDS));
     assertEquals(strategy==NotificationWorker.Strategy.DB_INLINE?1:0,pool.getHikariPoolMXBean().getActiveConnections());
     provider.release.countDown();assertTrue(result.get(5,TimeUnit.SECONDS));assertEquals("SENT",status(id));
    }finally{provider.release.countDown();executor.shutdownNow();}
   }
  }
 }
 @Test void threeLockStrategiesHandleConcurrentDuplicates() throws Exception {
  int student=1;
  for(var strategy:List.of(NotificationWorker.Strategy.DB_INLINE,NotificationWorker.Strategy.OPTIMISTIC,NotificationWorker.Strategy.REDIS_OUTBOX)){
   var req=request(student,student);student++;service.enroll(req);String id=event(req);
   try(var provider=new Provider()){
    provider.delayMs=100;var executor=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
    try{
     var one=executor.submit(()->{start.await();return worker(provider,30000).process(id,strategy);});
     var two=executor.submit(()->{start.await();return worker(provider,30000).process(id,strategy);});
     start.countDown();boolean a=one.get(10,TimeUnit.SECONDS),b=two.get(10,TimeUnit.SECONDS);
     assertTrue(a^b);assertEquals(1,provider.calls.get());assertEquals("SENT",status(id));
    }finally{executor.shutdownNow();}
   }
  }
 }
 @Test void versionConflictPreventsSecondOwnerFromCallingProvider() throws Exception {
  var request=request(1,1);service.enroll(request);String id=event(request);
  Long first=store.eligibleVersion(id),second=store.eligibleVersion(id);
  assertNotNull(first);assertEquals(first,second);
  var winner=store.claimVersion(id,first,30000);
  assertNotNull(winner);assertNull(store.claimVersion(id,second,30000));
  try(var provider=new Provider()){
   new NotificationGateway(provider.url(),5000).send(winner);assertTrue(store.sent(winner));
   assertFalse(worker(provider,30000).process(id,NotificationWorker.Strategy.OPTIMISTIC));
   assertEquals(1,provider.calls.get());assertEquals("SENT",status(id));
  }
 }
 @Test void twoWorkersDoNotSendSameEventTwice() throws Exception {
  var r=request(1,1);service.enroll(r);String id=event(r);
  try(var provider=new Provider()){
   provider.release=new CountDownLatch(1);var first=worker(provider,30000);var second=worker(provider,30000);
   var executor=Executors.newSingleThreadExecutor();
   try{
    var pending=executor.submit(()->first.process(id));assertTrue(provider.entered.await(5,TimeUnit.SECONDS));
    assertFalse(second.process(id));provider.release.countDown();assertTrue(pending.get(5,TimeUnit.SECONDS));
    assertEquals(1,provider.calls.get());assertEquals(1,provider.delivered.size());
   }finally{provider.release.countDown();executor.shutdownNow();}
  }
 }
 @Test void expiredRedisAndDbLeasesAllowRetryButProviderDeduplicates() throws Exception {
  var r=request(1,1);service.enroll(r);String id=event(r);
  try(var provider=new Provider()){
   provider.release=new CountDownLatch(1);var first=worker(provider,50);var second=worker(provider,50);
   var executor=Executors.newFixedThreadPool(2);
   try{
    var pending=executor.submit(()->first.process(id));assertTrue(provider.entered.await(5,TimeUnit.SECONDS));
    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
    while(redis.getLock("notification:"+id).isLocked()&&System.nanoTime()<deadline)Thread.sleep(10);
    due(id);var replacement=executor.submit(()->second.process(id));
    deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
    while(provider.calls.get()<2&&System.nanoTime()<deadline)Thread.sleep(10);
    assertEquals(2,provider.calls.get());provider.release.countDown();
    assertTrue(replacement.get(5,TimeUnit.SECONDS));assertFalse(pending.get(5,TimeUnit.SECONDS));
    assertEquals(1,provider.delivered.size());assertEquals("SENT",status(id));
   }finally{provider.release.countDown();executor.shutdownNow();}
  }
 }
 @Test void acceptedButLostResponseRetriesSameKey() throws Exception {
  var r=request(1,1);service.enroll(r);String id=event(r);
  try(var provider=new Provider()){
   provider.loseResponse=true;var worker=worker(provider,30000);
   worker.process(id); // HTTP implementation may retry internally; both outcomes must remain idempotent.
   if(!status(id).equals("SENT")){due(id);assertTrue(worker.process(id));}
   assertEquals(1,provider.delivered.size());assertEquals("SENT",status(id));
  }
 }
 @Test void crashedWorkerAfterProviderAcceptanceRecoversAndStaleOwnerCannotAck() throws Exception {
  var r=request(1,1);service.enroll(r);String id=event(r);
  try(var provider=new Provider()){
   var old=store.claim(id,30000);new NotificationGateway(provider.url(),1000).send(old);
   // Simulate process death: no ack/finally. Recovery happens after DB lease expiry.
   due(id);assertTrue(worker(provider,30000).process(id));
   assertFalse(store.sent(old));assertEquals(1,provider.delivered.size());assertEquals("SENT",status(id));
  }
 }
 @Test void permanentFailureRequiresReviewAndTransientFailureRetries() throws Exception {
  var r=request(1,1);service.enroll(r);String id=event(r);
  try(var provider=new Provider()){
   provider.status=503;var worker=worker(provider,30000);assertFalse(worker.process(id));assertEquals("PENDING",status(id));
   due(id);provider.status=400;assertFalse(worker.process(id));assertEquals("REVIEW",status(id));
   assertEquals(0,provider.delivered.size());
  }
 }
 @Test void compareNotificationStrategies() throws Exception {
  // Controlled notification-only benchmark: same DB, pool, provider latency and worker count.
  var results=new ArrayList<Map<String,Object>>();
  var strategies=NotificationWorker.Strategy.values();
  for(int round=0;round<3;round++)for(int offset=0;offset<3;offset++){
   var selected=strategies[(round+offset)%3];
   try(var provider=new Provider();var limited=new HikariDataSource()){
    provider.delayMs=100;
    limited.setJdbcUrl(pool.getJdbcUrl());limited.setUsername(pool.getUsername());limited.setPassword(pool.getPassword());
    limited.setMaximumPoolSize(2);limited.setMinimumIdle(2);limited.setConnectionTimeout(5000);
    limited.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
    var measurements=new Metrics(true);limited.setPoolName("notificationPool");
    measurements.postProcessBeforeInitialization(limited,"notificationPool");
    var db=new org.springframework.jdbc.core.JdbcTemplate(limited);db.queryForObject("SELECT 1",Integer.class);
    var testStore=new NotificationStore(db);var beans=new StaticListableBeanFactory();beans.addBean("redis",redis);
    var worker=new NotificationWorker(testStore,new NotificationGateway(provider.url(),3000),beans.getBeanProvider(RedissonClient.class),
      measurements,new org.springframework.jdbc.datasource.DataSourceTransactionManager(limited),true,false,selected,30000,5);
    // One unmeasured delivery per strategy warms HTTP and pool paths equally.
    String warm=UUID.randomUUID().toString();db.update("INSERT INTO notification_outbox(id,request_id,run_id,event_type,payload) VALUES(?,?,?,'CONFIRMED','{}')",warm,warm,run);
    assertTrue(worker.process(warm));provider.delivered.clear();provider.calls.set(0);
    var before=measurements.snapshot();
    var ids=new ArrayList<String>();
    for(int i=0;i<12;i++){String id=UUID.randomUUID().toString();ids.add(id);db.update("INSERT INTO notification_outbox(id,request_id,run_id,event_type,payload) VALUES(?,?,?,'CONFIRMED','{}')",id,id,run);}
    var durations=Collections.synchronizedList(new ArrayList<Double>());
    var reads=Collections.synchronizedList(new ArrayList<Double>());
    var readFailures=new AtomicInteger();var running=new AtomicBoolean(true);var maxWaiting=new AtomicInteger();
    var readExecutor=Executors.newSingleThreadExecutor();var senders=Executors.newFixedThreadPool(6);
    var readTask=readExecutor.submit(()->{
     while(running.get()){
      maxWaiting.accumulateAndGet(limited.getHikariPoolMXBean().getThreadsAwaitingConnection(),Math::max);
      long started=System.nanoTime();
      try{db.queryForObject("SELECT 1",Integer.class);}catch(RuntimeException e){readFailures.incrementAndGet();}
      reads.add((System.nanoTime()-started)/1e6);
      try{Thread.sleep(10);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
     }
    });
    long start=System.nanoTime();int confirmed=0;
    try{
     var futures=new ArrayList<Future<Boolean>>();
     for(String id:ids)futures.add(senders.submit(()->{boolean ok=worker.process(id);durations.add((System.nanoTime()-start)/1e6);return ok;}));
     for(var future:futures)if(future.get(20,TimeUnit.SECONDS))confirmed++;
    }finally{running.set(false);readTask.get(5,TimeUnit.SECONDS);senders.shutdownNow();readExecutor.shutdownNow();}
    assertEquals(12,confirmed);assertEquals(12,provider.delivered.size());
    var row=new LinkedHashMap<String,Object>();row.put("round",round+1);row.put("strategy",selected.name());
    row.put("messages",12);row.put("confirmed",confirmed);row.put("providerUnique",provider.delivered.size());row.put("providerCalls",provider.calls.get());
    row.put("allCompletedMs",(System.nanoTime()-start)/1e6);row.put("completionP95Ms",p95(durations));
    row.put("readP95IncludingFailuresMs",p95(reads));row.put("readSamples",reads.size());row.put("readFailures",readFailures.get());
    row.put("sampledMaxWaiting",maxWaiting.get());row.put("metricsBefore",before);row.put("metricsAfter",measurements.snapshot());results.add(row);
   }
  }
  var report=Map.of("scope","controlled notification-only benchmark; not enrollment throughput or Redis universal superiority",
    "providerLatencyMs",100,"messagesPerRun",12,"workerThreads",6,"poolSize",2,"readPacingMs",10,"results",results);
  var path=java.nio.file.Path.of("target","notification-comparison.json");java.nio.file.Files.createDirectories(path.getParent());
  new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(path.toFile(),report);
 }
 static double p95(List<Double> values){var sorted=new ArrayList<>(values);Collections.sort(sorted);return sorted.isEmpty()?0:sorted.get((int)Math.ceil(sorted.size()*.95)-1);}

 @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="notification.load",matches="true")
 void compareNotificationLoad() throws Exception {
  NotificationLoadHarness.run(pool,"redis://"+redisContainer.getHost()+":"+redisContainer.getMappedPort(6379),run);
 }

}
