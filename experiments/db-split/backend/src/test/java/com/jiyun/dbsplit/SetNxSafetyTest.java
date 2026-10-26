package com.jiyun.dbsplit;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.redisson.client.codec.StringCodec;
import java.time.Duration;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@TestPropertySource(properties="experiment.mode=E")
class SetNxSafetyTest extends RedisGuardedSafetyTest {
 @Override @Test void redisWaitDoesNotBorrowDatabaseConnection() throws Exception {
  var r=request(1,1);
  var course=redis.<String>getBucket(facade.courseKey(r),StringCodec.INSTANCE);
  var student=redis.<String>getBucket(facade.studentKey(r),StringCodec.INSTANCE);
  assertTrue(course.setIfAbsent("blocker",Duration.ofSeconds(30)));
  var executor=Executors.newSingleThreadExecutor();
  try{
   var future=executor.submit(()->facade.enroll(r));
   long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
   while(!student.isExists() && System.nanoTime()<end)Thread.sleep(10);
   assertTrue(student.isExists());assertFalse(future.isDone());
   assertEquals(0,pool.getHikariPoolMXBean().getActiveConnections());
   assertEquals(0,pool.getHikariPoolMXBean().getThreadsAwaitingConnection());
   assertEquals(1,facade.releaseSetNx(facade.courseKey(r),"blocker").intValue());
   assertEquals("CONFIRMED",future.get(10,TimeUnit.SECONDS).status());
   assertFalse(student.isExists());assertFalse(course.isExists());consistent();
  }finally{facade.releaseSetNx(facade.courseKey(r),"blocker");executor.shutdownNow();}
 }
 @Test void expiredOwnerCannotDeleteNewOwnersLock() throws Exception {
  String key="setnx-test:"+java.util.UUID.randomUUID();
  var bucket=redis.<String>getBucket(key,StringCodec.INSTANCE);
  assertTrue(bucket.setIfAbsent("old-owner",Duration.ofMillis(100)));
  long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
  while(bucket.isExists() && System.nanoTime()<end)Thread.sleep(10);
  assertFalse(bucket.isExists());
  assertTrue(bucket.setIfAbsent("new-owner",Duration.ofSeconds(30)));
  assertEquals(0,facade.releaseSetNx(key,"old-owner").intValue());
  assertEquals("new-owner",bucket.get());
  assertEquals(1,facade.releaseSetNx(key,"new-owner").intValue());
 }
}
