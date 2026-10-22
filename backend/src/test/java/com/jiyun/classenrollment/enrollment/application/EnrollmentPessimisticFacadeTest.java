package com.jiyun.classenrollment.enrollment.application;

import com.jiyun.classenrollment.common.error.EnrollmentException;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EnrollmentPessimisticFacadeTest {
    @Test
    void commitConnectionFailureIsReportedAsUnknownWithoutAutomaticRetry() throws Exception {
        var service = mock(EnrollmentService.class);
        var redis = mock(RedissonClient.class);
        var student = mock(RLock.class);
        when(redis.getLock("lock:student:1")).thenReturn(student);
        when(student.tryLock(10, TimeUnit.SECONDS)).thenReturn(true);
        when(student.isHeldByCurrentThread()).thenReturn(true);
        when(service.completeEnrollmentWithPessimisticLock(1L, 10L))
                .thenThrow(new org.springframework.transaction.TransactionSystemException("commit reply lost"));
        var error = assertThrows(EnrollmentException.class,
                () -> new EnrollmentRedissonFacade(service, redis,
                        CourseLockStrategy.PESSIMISTIC).enroll(1L, 10L));
        assertEquals("ENROLLMENT_RESULT_UNKNOWN", error.getCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatus());
        verify(service, times(1)).completeEnrollmentWithPessimisticLock(1L, 10L);
        verify(student).unlock();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void unlockFailureDoesNotReplaceCommittedSuccess(boolean expired) throws Exception {
        var service = mock(EnrollmentService.class);
        var redis = mock(RedissonClient.class);
        var student = mock(RLock.class);
        when(redis.getLock("lock:student:1")).thenReturn(student);
        when(student.tryLock(10, TimeUnit.SECONDS)).thenReturn(true);
        when(student.isHeldByCurrentThread()).thenReturn(true);
        when(service.completeEnrollmentWithPessimisticLock(1L, 10L)).thenReturn(99L);
        RuntimeException failure = expired ? new IllegalMonitorStateException("expired between check and unlock")
                : new org.redisson.client.RedisException("connection lost after commit");
        doThrow(failure).when(student).unlock();
        assertEquals(99L, new EnrollmentRedissonFacade(service, redis,
                CourseLockStrategy.PESSIMISTIC).enroll(1L, 10L));
    }

    @Test
    void interruptedWaitReturns503AndPreservesInterrupt() throws Exception {
        var service = mock(EnrollmentService.class);
        var redis = mock(RedissonClient.class);
        var student = mock(RLock.class);
        when(redis.getLock("lock:student:1")).thenReturn(student);
        when(student.tryLock(10, TimeUnit.SECONDS)).thenThrow(new InterruptedException());
        try {
            var failure = assertThrows(EnrollmentException.class,
                    () -> new EnrollmentRedissonFacade(service, redis,
                            CourseLockStrategy.PESSIMISTIC).enroll(1L, 10L));
            assertEquals("LOCK_INTERRUPTED", failure.getCode());
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.getStatus());
            assertTrue(Thread.currentThread().isInterrupted());
            verifyNoInteractions(service);
            verify(student, never()).unlock();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void usesOnlyStudentRedisLockAndReleasesItAfterWriteReturns() throws Exception {
        var service = mock(EnrollmentService.class);
        var redis = mock(RedissonClient.class);
        var student = mock(RLock.class);
        when(redis.getLock("lock:student:1")).thenReturn(student);
        when(student.tryLock(10, TimeUnit.SECONDS)).thenReturn(true);
        when(student.isHeldByCurrentThread()).thenReturn(true);
        when(service.completeEnrollmentWithPessimisticLock(1L, 10L)).thenReturn(99L);
        var facade = new EnrollmentRedissonFacade(service, redis, CourseLockStrategy.PESSIMISTIC);
        assertEquals(99L, facade.enroll(1L, 10L));
        var order = inOrder(student, service);
        order.verify(student).tryLock(10, TimeUnit.SECONDS);
        order.verify(service).validateForEnrollment(1L, 10L);
        order.verify(service).completeEnrollmentWithPessimisticLock(1L, 10L);
        order.verify(student).isHeldByCurrentThread();
        order.verify(student).unlock();
        verify(redis, never()).getLock("lock:course:10");
        verify(service, never()).completeEnrollment(1L, 10L);
    }

    @Test
    void databaseLockFailureReturns503AndReleasesStudentLock() throws Exception {
        var service = mock(EnrollmentService.class);
        var redis = mock(RedissonClient.class);
        var student = mock(RLock.class);
        when(redis.getLock("lock:student:1")).thenReturn(student);
        when(student.tryLock(10, TimeUnit.SECONDS)).thenReturn(true);
        when(student.isHeldByCurrentThread()).thenReturn(true);
        var cause = new CannotAcquireLockException("lock wait timeout");
        when(service.completeEnrollmentWithPessimisticLock(1L, 10L)).thenThrow(cause);
        var facade = new EnrollmentRedissonFacade(service, redis, CourseLockStrategy.PESSIMISTIC);
        var failure = assertThrows(EnrollmentException.class, () -> facade.enroll(1L, 10L));
        assertEquals("DB_LOCK_FAILURE", failure.getCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.getStatus());
        assertSame(cause, failure.getCause());
        verify(student).unlock();
        verify(redis, never()).getLock("lock:course:10");
    }

    @Test
    void failedValidationDoesNotStartPessimisticWrite() throws Exception {
        var service = mock(EnrollmentService.class);
        var redis = mock(RedissonClient.class);
        var student = mock(RLock.class);
        when(redis.getLock("lock:student:1")).thenReturn(student);
        when(student.tryLock(10, TimeUnit.SECONDS)).thenReturn(true);
        when(student.isHeldByCurrentThread()).thenReturn(true);
        doThrow(new EnrollmentException("CREDIT_LIMIT_EXCEEDED", "credit", HttpStatus.CONFLICT))
                .when(service).validateForEnrollment(1L, 10L);
        var facade = new EnrollmentRedissonFacade(service, redis, CourseLockStrategy.PESSIMISTIC);
        assertThrows(EnrollmentException.class, () -> facade.enroll(1L, 10L));
        verify(service, never()).completeEnrollmentWithPessimisticLock(1L, 10L);
        verify(student).unlock();
    }
}
