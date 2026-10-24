package com.jiyun.dbsplit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.*;
import org.testcontainers.containers.*;
import org.testcontainers.junit.jupiter.Container;
import org.redisson.api.RedissonClient;
import com.zaxxer.hikari.HikariDataSource;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@TestPropertySource(properties="experiment.mode=C")
class RedisGuardedSafetyTest extends EnrollmentSafetyTest {
 @Container static final MySQLContainer<?> academicMysql=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> redisContainer=new GenericContainer<>("redis:7.4.2").withExposedPorts(6379);
 @DynamicPropertySource static void extraProperties(DynamicPropertyRegistry r){
  r.add("experiment.academic.url",academicMysql::getJdbcUrl);
  r.add("experiment.academic.username",academicMysql::getUsername);
  r.add("experiment.academic.password",academicMysql::getPassword);
  r.add("experiment.redis.address",()->"redis://"+redisContainer.getHost()+":"+redisContainer.getMappedPort(6379));
 }
 @Autowired EnrollmentFacade facade;
 @Autowired RedissonClient redis;
 @Autowired HikariDataSource pool;
 // Inherited tests exercise authoritative DB guards even when callers bypass Redis.
 @Test void redisWaitDoesNotBorrowDatabaseConnection() throws Exception {
  var r=request(1,1);var lock=redis.getLock(facade.courseKey(r));lock.lock();
  var executor=Executors.newSingleThreadExecutor();
  try{
   var future=executor.submit(()->facade.enroll(r));
   var studentLock=redis.getLock(facade.studentKey(r));
   long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
   while(!studentLock.isLocked() && System.nanoTime()<end)Thread.sleep(10);
   assertTrue(studentLock.isLocked());assertFalse(future.isDone());
   assertEquals(1,facade.inFlight());
   assertEquals(0,pool.getHikariPoolMXBean().getActiveConnections());
   assertEquals(0,pool.getHikariPoolMXBean().getThreadsAwaitingConnection());
   lock.unlock();
   assertEquals("CONFIRMED",future.get(10,TimeUnit.SECONDS).status());
   assertFalse(studentLock.isLocked());assertFalse(lock.isLocked());assertEquals(0,facade.inFlight());
   assertEquals(1L,fixture.integrity(run).get("confirmed"));consistent();
  }finally{if(lock.isHeldByCurrentThread())lock.unlock();executor.shutdownNow();}
 }
 @Test void guardedConcurrentCapacityAndIdempotentRetry() throws Exception {
  var executor=Executors.newFixedThreadPool(20);
  try{
   var jobs=new ArrayList<Callable<String>>();
   for(int i=1;i<=20;i++){var r=request(i,1);jobs.add(()->{try{return facade.enroll(r).status();}catch(EnrollmentService.Rejected e){return e.code;}});}
   var values=new ArrayList<String>();for(var f:executor.invokeAll(jobs))values.add(f.get());
   assertEquals(3,Collections.frequency(values,"CONFIRMED"));assertEquals(17,Collections.frequency(values,"COURSE_FULL"));consistent();
   var r=request(20,2);facade.enroll(r);facade.enroll(r);facade.cancel(r);facade.cancel(r);consistent();
   assertEquals(3L,fixture.integrity(run).get("confirmed"));
  }finally{executor.shutdownNow();}
 }
}
