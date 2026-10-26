package com.jiyun.dbsplit;
import org.junit.jupiter.api.*;
import org.redisson.api.*;
import org.springframework.beans.factory.ObjectProvider;
import java.util.concurrent.TimeUnit;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class EnrollmentFacadeTest {
 EnrollmentService service;RedissonClient redis;RLock student,course;EnrollmentFacade facade;
 EnrollmentService.Request request=new EnrollmentService.Request("request","run",1,202602,1);
 @BeforeEach @SuppressWarnings("unchecked") void setup() throws Exception {
  service=mock(EnrollmentService.class,withSettings().mockMaker("mock-maker-subclass"));redis=mock(RedissonClient.class,withSettings().mockMaker("mock-maker-subclass"));
  student=mock(RLock.class,withSettings().mockMaker("mock-maker-subclass"));course=mock(RLock.class,withSettings().mockMaker("mock-maker-subclass"));
  ObjectProvider<RedissonClient> provider=mock(ObjectProvider.class,withSettings().mockMaker("mock-maker-subclass"));when(provider.getIfAvailable()).thenReturn(redis);
  facade=new EnrollmentFacade(service,new Metrics(true),provider,DatabaseConfig.Mode.C);
  when(redis.getLock(facade.studentKey(request))).thenReturn(student);
  when(redis.getLock(facade.courseKey(request))).thenReturn(course);
  when(student.tryLock(anyLong(),eq(TimeUnit.NANOSECONDS))).thenReturn(true);
  when(course.tryLock(anyLong(),eq(TimeUnit.NANOSECONDS))).thenReturn(true);
 }
 @Test @SuppressWarnings("unchecked") void hybridSkipsCourseRedisAndRetainsStudentLock() throws Exception {
  ObjectProvider<RedissonClient> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(redis);
  var hybrid=new EnrollmentFacade(service,new Metrics(true),provider,DatabaseConfig.Mode.D);
  when(service.enroll(request)).thenReturn(new EnrollmentService.Result("request","CONFIRMED"));
  assertEquals("CONFIRMED",hybrid.enroll(request).status());
  verify(redis,never()).getLock(hybrid.courseKey(request));verifyNoInteractions(course);
  var order=inOrder(student,service);
  order.verify(student).tryLock(anyLong(),eq(TimeUnit.NANOSECONDS));
  order.verify(service).enroll(request);order.verify(student).unlock();
 }
 @Test void timeoutNeverEntersDbAndReleasesStudent() throws Exception {
  when(course.tryLock(anyLong(),eq(TimeUnit.NANOSECONDS))).thenReturn(false);
  assertEquals("REDIS_LOCK_TIMEOUT",assertThrows(EnrollmentFacade.Unavailable.class,()->facade.enroll(request)).code);
  verifyNoInteractions(service);verify(student).unlock();verify(course,never()).unlock();assertEquals(0,facade.inFlight());
 }
 @Test void unlockFailureDoesNotReplaceCommittedResult(){
  var result=new EnrollmentService.Result("request","CONFIRMED");when(service.enroll(request)).thenReturn(result);
  doThrow(new IllegalMonitorStateException("lease lost")).when(course).unlock();
  assertSame(result,facade.enroll(request));verify(student).unlock();
 }
 @Test void databaseFailureReleasesBothLocks(){
  when(service.enroll(request)).thenThrow(new EnrollmentService.Rejected("COURSE_FULL"));
  assertThrows(EnrollmentService.Rejected.class,()->facade.enroll(request));
  var order=inOrder(student,course,service);
  try{order.verify(student).tryLock(anyLong(),eq(TimeUnit.NANOSECONDS));order.verify(course).tryLock(anyLong(),eq(TimeUnit.NANOSECONDS));}catch(InterruptedException e){throw new AssertionError(e);}
  order.verify(service).enroll(request);order.verify(course).unlock();order.verify(student).unlock();
 }
 @Test void redisFailureDoesNotFallBackToDatabase() throws Exception{
  when(student.tryLock(anyLong(),eq(TimeUnit.NANOSECONDS))).thenThrow(new IllegalStateException("Redis unavailable"));
  assertEquals("REDIS_UNAVAILABLE",assertThrows(EnrollmentFacade.Unavailable.class,()->facade.enroll(request)).code);
  verifyNoInteractions(service);
 }
 @Test void lockHandleFailureReleasesPreviouslyAcquiredStudentLock(){
  when(redis.getLock(facade.courseKey(request))).thenThrow(new IllegalStateException("client shut down"));
  assertEquals("REDIS_UNAVAILABLE",assertThrows(EnrollmentFacade.Unavailable.class,()->facade.enroll(request)).code);
  verifyNoInteractions(service);verify(student).unlock();verifyNoInteractions(course);assertEquals(0,facade.inFlight());
 }
 @Test void interruptedWaitRestoresInterruptAndReleasesStudent() throws Exception {
  when(course.tryLock(anyLong(),eq(TimeUnit.NANOSECONDS))).thenThrow(new InterruptedException("interrupted"));
  try {
   assertEquals("REDIS_LOCK_INTERRUPTED",assertThrows(EnrollmentFacade.Unavailable.class,()->facade.enroll(request)).code);
   assertTrue(Thread.currentThread().isInterrupted());
   verifyNoInteractions(service);verify(student).unlock();verify(course,never()).unlock();assertEquals(0,facade.inFlight());
  } finally {Thread.interrupted();}
 }

}
