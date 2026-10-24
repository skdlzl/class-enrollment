package com.jiyun.dbsplit;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@TestPropertySource(properties="experiment.mode=D")
class HybridSafetyTest extends RedisGuardedSafetyTest {
 @Override @Test void redisWaitDoesNotBorrowDatabaseConnection() throws Exception {
  var r=request(1,1);var lock=redis.getLock(facade.studentKey(r));lock.lock();
  var executor=Executors.newSingleThreadExecutor();
  try{
   var future=executor.submit(()->facade.enroll(r));
   long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
   while(facade.inFlight()==0 && System.nanoTime()<end)Thread.sleep(10);
   assertEquals(1,facade.inFlight());assertFalse(future.isDone());
   assertEquals(0,pool.getHikariPoolMXBean().getActiveConnections());
   assertEquals(0,pool.getHikariPoolMXBean().getThreadsAwaitingConnection());
   lock.unlock();
   assertEquals("CONFIRMED",future.get(10,TimeUnit.SECONDS).status());
   assertFalse(lock.isLocked());assertEquals(0,facade.inFlight());consistent();
   var state=http.getForObject("http://127.0.0.1:"+port+"/experiment/state",Map.class);
   assertEquals("HYBRID_GUARDED",state.get("strategy"));
   assertEquals(false,((Map<?,?>)state.get("redisGuard")).get("course"));
   assertFalse(((Map<?,?>)metrics.snapshot().get("phases")).containsKey("courseRedis.wait"));
  }finally{if(lock.isHeldByCurrentThread())lock.unlock();executor.shutdownNow();}
 }
 @Test void hybridSerializesSameStudentAndEnforcesCredits() throws Exception {
  jdbc.update("UPDATE student_term SET max_credits=3 WHERE run_id=? AND student_id=1",run);
  var executor=Executors.newFixedThreadPool(2);
  try{
   List<Callable<String>> jobs=new ArrayList<>();
   for(int course=1;course<=2;course++){
    var r=request(1,course);
    jobs.add(()->{try{return facade.enroll(r).status();}catch(EnrollmentService.Rejected e){return e.code;}});
   }
   var outcomes=new ArrayList<String>();
   for(var f:executor.invokeAll(jobs))outcomes.add(f.get());
   assertEquals(1,Collections.frequency(outcomes,"CONFIRMED"));
   assertEquals(1,Collections.frequency(outcomes,"CREDIT_LIMIT_EXCEEDED"));consistent();
  }finally{executor.shutdownNow();}
 }
}
