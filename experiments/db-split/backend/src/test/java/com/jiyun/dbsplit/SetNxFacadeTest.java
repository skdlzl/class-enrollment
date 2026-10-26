package com.jiyun.dbsplit;
import org.junit.jupiter.api.*;
import org.redisson.api.*;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.ObjectProvider;
import java.time.Duration;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class SetNxFacadeTest {
 EnrollmentService service; RedissonClient redis; RBucket<String> student,course; RScript script;
 EnrollmentFacade facade;
 EnrollmentService.Request request=new EnrollmentService.Request("request","run",1,202602,1);
 @BeforeEach @SuppressWarnings("unchecked") void setup(){
  service=mock(EnrollmentService.class);redis=mock(RedissonClient.class);
  student=mock(RBucket.class);course=mock(RBucket.class);script=mock(RScript.class);
  ObjectProvider<RedissonClient> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(redis);
  facade=new EnrollmentFacade(service,new Metrics(true),provider,DatabaseConfig.Mode.E);
  when(redis.<String>getBucket(facade.studentKey(request),StringCodec.INSTANCE)).thenReturn(student);
  when(redis.<String>getBucket(facade.courseKey(request),StringCodec.INSTANCE)).thenReturn(course);
  when(redis.getScript(StringCodec.INSTANCE)).thenReturn(script);
  when(student.setIfAbsent(anyString(),eq(Duration.ofSeconds(30)))).thenReturn(true);
  when(course.setIfAbsent(anyString(),eq(Duration.ofSeconds(30)))).thenReturn(true);
 }
 @Test void bothLeasesAcquiredBeforeDatabase(){
  var result=new EnrollmentService.Result("request","CONFIRMED");when(service.enroll(request)).thenReturn(result);
  assertSame(result,facade.enroll(request));
  var order=inOrder(student,course,service);
  order.verify(student).setIfAbsent(anyString(),eq(Duration.ofSeconds(30)));
  order.verify(course).setIfAbsent(anyString(),eq(Duration.ofSeconds(30)));
  order.verify(service).enroll(request);
  verify(redis,never()).getLock(anyString());assertEquals(0,facade.inFlight());
 }
 @Test void redisErrorNeverFallsBackToDb(){
  when(course.setIfAbsent(anyString(),any(Duration.class))).thenThrow(new IllegalStateException());
  assertEquals("REDIS_UNAVAILABLE",assertThrows(EnrollmentFacade.Unavailable.class,()->facade.enroll(request)).code);
  verifyNoInteractions(service);verify(redis).getScript(StringCodec.INSTANCE);assertEquals(0,facade.inFlight());
 }
 @Test void interruptedCallerNeverEntersDatabase(){
  Thread.currentThread().interrupt();
  try{
   assertEquals("REDIS_LOCK_INTERRUPTED",assertThrows(EnrollmentFacade.Unavailable.class,()->facade.enroll(request)).code);
   assertTrue(Thread.currentThread().isInterrupted());verifyNoInteractions(service,student,course);
  }finally{Thread.interrupted();}
 }
}
