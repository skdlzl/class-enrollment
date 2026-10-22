package com.jiyun.classenrollment.enrollment.application;

import com.jiyun.classenrollment.common.error.EnrollmentException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.redisson.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EnrollmentDbAdmissionTest {
    @Test void limitsConcurrentTransactionsAndTimeoutNeverInvokesWrite() throws Exception {
        var gate = new EnrollmentDbAdmission(2, 20, 500);
        var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        java.util.function.Supplier<Long> blocked = () -> {
            entered.countDown();
            try { if(!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("release timeout"); }
            catch (InterruptedException e) { throw new RuntimeException(e); }
            return 1L;
        };
        try {
            var a=executor.submit(() -> gate.execute(blocked));
            var b=executor.submit(() -> gate.execute(blocked));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertEquals(2, gate.snapshot().get("active"));
            var error=assertThrows(EnrollmentException.class, () -> gate.execute(() -> {throw new AssertionError("must not enter");}));
            assertEquals("DB_ADMISSION_TIMEOUT", error.getCode());
            release.countDown(); assertEquals(1L,a.get(3,TimeUnit.SECONDS)); assertEquals(1L,b.get(3,TimeUnit.SECONDS));
            assertEquals(7L,gate.execute(() -> 7L));
            assertEquals(0,gate.snapshot().get("active"));
            assertEquals(1L,gate.snapshot().get("timeouts"));
        } finally { release.countDown(); executor.shutdownNow(); }
    }
    @Test void failureAndInterruptionDoNotLeakSlots() {
        var gate=new EnrollmentDbAdmission(1,20,500);
        assertThrows(IllegalStateException.class,()->gate.execute(()->{throw new IllegalStateException("rollback");}));
        Thread.currentThread().interrupt();
        try {
            assertEquals("DB_ADMISSION_INTERRUPTED",assertThrows(EnrollmentException.class,
                ()->gate.execute(()->{throw new AssertionError("interrupted");})).getCode());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {Thread.interrupted();}
        assertEquals(1L,gate.execute(()->1L));
        assertEquals(0,gate.snapshot().get("waiting"));
    }
    @ParameterizedTest @EnumSource(value=CourseLockStrategy.class,names={"PESSIMISTIC","DB_ONLY"})
    void bothStrategiesReleaseSlotAfterTransactionalProxyReturns(CourseLockStrategy strategy) throws Exception {
        var gate=new EnrollmentDbAdmission(1,20,500);
        var service=mock(EnrollmentService.class); var redis=mock(RedissonClient.class);var lock=mock(RLock.class);
        if(strategy==CourseLockStrategy.PESSIMISTIC) {
            when(redis.getLock("lock:student:1")).thenReturn(lock);
            when(lock.tryLock(10,TimeUnit.SECONDS)).thenReturn(true);
            when(lock.isHeldByCurrentThread()).thenReturn(true);
            when(service.completeEnrollmentWithPessimisticLock(1L,2L)).thenAnswer(i->{
                assertEquals(1,gate.snapshot().get("active"));return 3L;});
        } else when(service.enrollWithDatabaseLocks(1L,2L)).thenAnswer(i->{
            assertEquals(1,gate.snapshot().get("active"));return 3L;});
        var facade=new EnrollmentRedissonFacade(service,redis,strategy);
        ReflectionTestUtils.setField(facade,"dbAdmission",gate);
        assertEquals(3L,facade.enroll(1L,2L));assertEquals(0,gate.snapshot().get("active"));
        if(strategy==CourseLockStrategy.DB_ONLY)verifyNoInteractions(redis);
        else verify(lock).unlock();
        verify(service,never()).validateForEnrollment(anyLong(),anyLong());
    }
}
