package com.jiyun.dbsplit;

import com.jiyun.dbsplit.lockapi.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LockApiSafetyTest extends EnrollmentSafetyTest {
 @Container static final GenericContainer<?> redisContainer=new GenericContainer<>("redis:7.4.2").withExposedPorts(6379);
 RedissonClient redis;
 @BeforeEach void client(){var c=new Config();c.setThreads(2).setNettyThreads(2);c.useSingleServer().setAddress("redis://"+redisContainer.getHost()+":"+redisContainer.getMappedPort(6379));redis=Redisson.create(c);}
 @AfterEach void closeRedis(){if(redis!=null)redis.shutdown();}
 static class Api implements AutoCloseable {
  final HttpServer server;final ExecutorService executor=Executors.newCachedThreadPool();
  final AtomicInteger calls=new AtomicInteger();volatile boolean eligible=true;volatile int status=200;
  volatile CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(0);
  Api()throws Exception{
   server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(executor);
   server.createContext("/eligibility",ex->{calls.incrementAndGet();entered.countDown();
    try{release.await(5,TimeUnit.SECONDS);byte[] b=("{\"eligible\":"+eligible+"}").getBytes(StandardCharsets.UTF_8);ex.sendResponseHeaders(status,b.length);try(var out=ex.getResponseBody()){out.write(b);}}
    catch(InterruptedException e){Thread.currentThread().interrupt();}finally{ex.close();}
   });server.start();
  }
  String url(){return "http://127.0.0.1:"+server.getAddress().getPort()+"/eligibility";}
  public void close(){release.countDown();server.stop(0);executor.shutdownNow();}
 }
 LockApiEnrollment facade(LockApiEnrollment.Strategy strategy,Api api,JdbcTemplate db){
  var beans=new StaticListableBeanFactory();beans.addBean("redis",redis);
  var env=new MockEnvironment().withProperty("experiment.lock-api.strategy",strategy.name()).withProperty("experiment.lock-api.url",api.url());
  return new LockApiEnrollment(db,metrics,tx,beans.getBeanProvider(RedissonClient.class),env,DatabaseConfig.Mode.B);
 }
 List<String> pair(LockApiEnrollment f,EnrollmentService.Request a,EnrollmentService.Request b)throws Exception{
  var workers=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
  try{var futures=new ArrayList<Future<String>>();for(var r:List.of(a,b))futures.add(workers.submit(()->{start.await();try{return f.enroll(r).status();}catch(EnrollmentService.Rejected e){return e.code;}}));
   start.countDown();return List.of(futures.get(0).get(15,TimeUnit.SECONDS),futures.get(1).get(15,TimeUnit.SECONDS));
  }finally{workers.shutdownNow();}
 }
 @ParameterizedTest @EnumSource(value=LockApiEnrollment.Strategy.class,names="OFF",mode=EnumSource.Mode.EXCLUDE)
 void creditAndCapacityAreProtected(LockApiEnrollment.Strategy strategy)throws Exception{
  try(var api=new Api()){
   var f=facade(strategy,api,jdbc);jdbc.update("UPDATE student_term SET max_credits=3 WHERE run_id=? AND student_id=1",run);
   var outcomes=pair(f,request(1,1),request(1,2));assertEquals(1,Collections.frequency(outcomes,"CONFIRMED"));assertEquals(1,Collections.frequency(outcomes,"CREDIT_LIMIT_EXCEEDED"));consistent();
   jdbc.update("UPDATE offered_course SET capacity=1 WHERE run_id=? AND id=3",run);
   outcomes=pair(f,request(2,3),request(3,3));assertEquals(1,Collections.frequency(outcomes,"CONFIRMED"));assertEquals(1,Collections.frequency(outcomes,"COURSE_FULL"));consistent();
  }
 }
 @ParameterizedTest @EnumSource(value=LockApiEnrollment.Strategy.class,names="OFF",mode=EnumSource.Mode.EXCLUDE)
 void scheduleAndLostResponse(LockApiEnrollment.Strategy strategy)throws Exception{
  try(var api=new Api()){
   var f=facade(strategy,api,jdbc);jdbc.update("UPDATE offering_schedule SET day_no=1 WHERE run_id=? AND course_id=2",run);
   var outcomes=pair(f,request(1,1),request(1,2));assertEquals(1,Collections.frequency(outcomes,"CONFIRMED"));assertEquals(1,Collections.frequency(outcomes,"SCHEDULE_CONFLICT"));consistent();
   var r=request(2,3);assertEquals("CONFIRMED",f.enroll(r).status());int before=api.calls.get();assertEquals("CONFIRMED",f.enroll(r).status());assertEquals(before,api.calls.get(),"Known request skips repeated external check");consistent();
  }
 }
 @ParameterizedTest @EnumSource(value=LockApiEnrollment.Strategy.class,names="OFF",mode=EnumSource.Mode.EXCLUDE)
 void apiFailureNeverStoresEnrollment(LockApiEnrollment.Strategy strategy)throws Exception{
  try(var api=new Api()){
   var f=facade(strategy,api,jdbc);api.eligible=false;assertEquals("ELIGIBILITY_DENIED",assertThrows(EnrollmentService.Rejected.class,()->f.enroll(request(1,1))).code);
   api.status=500;assertEquals("ELIGIBILITY_API_UNAVAILABLE",assertThrows(LockApiEnrollment.Unavailable.class,()->f.enroll(request(1,1))).code);
   assertEquals(0L,fixture.integrity(run).get("confirmed"));consistent();
   api.status=200;api.eligible=true;assertEquals("CONFIRMED",f.enroll(request(1,1)).status());consistent();
  }
 }
 @ParameterizedTest @EnumSource(value=LockApiEnrollment.Strategy.class,names="OFF",mode=EnumSource.Mode.EXCLUDE)
 void writeFailureRollsBackBothCounters(LockApiEnrollment.Strategy strategy)throws Exception{
  try(var api=new Api()){
   var broken=spy(jdbc);doThrow(new org.springframework.dao.DataIntegrityViolationException("injected insert failure")).when(broken)
    .update(eq("INSERT INTO enrollment(request_id,run_id,student_id,term_id,course_id) VALUES(?,?,?,?,?)"),any(Object[].class));
   assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->facade(strategy,api,broken).enroll(request(1,1)));
   assertEquals(0L,fixture.integrity(run).get("confirmed"));consistent();
   assertEquals("CONFIRMED",facade(strategy,api,jdbc).enroll(request(1,1)).status());consistent();
  }
 }
 @ParameterizedTest @EnumSource(value=LockApiEnrollment.Strategy.class,names="OFF",mode=EnumSource.Mode.EXCLUDE)
 void connectionOwnershipDuringApiAndTimeout(LockApiEnrollment.Strategy strategy)throws Exception{
  try(var api=new Api()){
   api.release=new CountDownLatch(1);var f=facade(strategy,api,jdbc);var executor=Executors.newSingleThreadExecutor();
   try{
    var future=executor.submit(()->f.enroll(request(1,1)));assertTrue(api.entered.await(5,TimeUnit.SECONDS));
    var pool=(com.zaxxer.hikari.HikariDataSource)jdbc.getDataSource();
    assertEquals(strategy==LockApiEnrollment.Strategy.PESSIMISTIC?1:0,pool.getHikariPoolMXBean().getActiveConnections());
    var error=assertThrows(ExecutionException.class,()->future.get(5,TimeUnit.SECONDS));assertInstanceOf(LockApiEnrollment.Unavailable.class,error.getCause());
    api.release.countDown();assertEquals(0L,fixture.integrity(run).get("confirmed"));consistent();
   }finally{api.release.countDown();executor.shutdownNow();}
  }
 }
 @Test void optimisticConflictRetriesExternalCheckAndWholeTransaction()throws Exception{
  try(var api=new Api()){
   api.entered=new CountDownLatch(2);api.release=new CountDownLatch(1);var f=facade(LockApiEnrollment.Strategy.OPTIMISTIC,api,jdbc);
   var executor=Executors.newFixedThreadPool(2);
   try{
    var a=executor.submit(()->f.enroll(request(1,1)));var b=executor.submit(()->f.enroll(request(1,2)));
    assertTrue(api.entered.await(5,TimeUnit.SECONDS));api.release.countDown();
    assertEquals("CONFIRMED",a.get(10,TimeUnit.SECONDS).status());assertEquals("CONFIRMED",b.get(10,TimeUnit.SECONDS).status());
    assertTrue(api.calls.get()>=3);assertEquals(6,jdbc.queryForObject("SELECT enrolled_credits FROM student_term WHERE run_id=? AND student_id=1",Integer.class,run));consistent();
   }finally{api.release.countDown();executor.shutdownNow();}
  }
 }
}
