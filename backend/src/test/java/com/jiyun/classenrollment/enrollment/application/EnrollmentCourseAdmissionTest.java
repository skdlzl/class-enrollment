package com.jiyun.classenrollment.enrollment.application;

import com.jiyun.classenrollment.common.error.EnrollmentException;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.springframework.http.HttpStatus;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class EnrollmentCourseAdmissionTest {
    @Test
    void fullCourseSkipsRedisCourseLockAndReleasesLocalPermit() throws Exception {
        EnrollmentService service = mock(EnrollmentService.class);
        RedissonClient redis = mock(RedissonClient.class);
        RLock student = mock(RLock.class), course = mock(RLock.class);
        when(redis.getLock("lock:student:1")).thenReturn(student);
        when(redis.getLock("lock:course:10")).thenReturn(course);
        when(student.tryLock(10, TimeUnit.SECONDS)).thenReturn(true);
        when(student.isHeldByCurrentThread()).thenReturn(true);
        doThrow(new EnrollmentException("COURSE_FULL", "full", HttpStatus.CONFLICT))
                .doNothing().when(service).validateCourseCapacity(10L);
        when(course.tryLock(anyLong(), eq(TimeUnit.NANOSECONDS))).thenReturn(true);
        when(course.isHeldByCurrentThread()).thenReturn(true);
        when(service.completeEnrollment(1L, 10L)).thenReturn(99L);
        var facade = new EnrollmentRedissonFacade(service, redis, true);
        assertEquals("COURSE_FULL", assertThrows(EnrollmentException.class,
                () -> facade.enroll(1L, 10L)).getCode());
        verify(course, never()).tryLock(anyLong(), any());
        verify(service, never()).completeEnrollment(anyLong(), anyLong());
        // The same course can enter again after the rejection.
        assertEquals(99L, facade.enroll(1L, 10L));
        // Commit-return precedes the distributed unlock (verified separately below).
        verify(student, times(2)).unlock();
        verify(course).unlock();
    }

    @Test
    void commitReturnsBeforeUnlockAndRedisWaitUsesRemainingBudget() throws Exception {
        EnrollmentService service = mock(EnrollmentService.class);
        RedissonClient redis = mock(RedissonClient.class);
        RLock student = mock(RLock.class), course = mock(RLock.class);
        when(redis.getLock("lock:student:1")).thenReturn(student);
        when(redis.getLock("lock:course:10")).thenReturn(course);
        when(student.tryLock(10, TimeUnit.SECONDS)).thenReturn(true);
        when(student.isHeldByCurrentThread()).thenReturn(true);
        when(course.tryLock(anyLong(), eq(TimeUnit.NANOSECONDS))).thenAnswer(call -> {
            long budget = call.getArgument(0);
            assertTrue(budget > 0 && budget <= TimeUnit.SECONDS.toNanos(10));
            return true;
        });
        when(course.isHeldByCurrentThread()).thenReturn(true);
        when(service.completeEnrollment(1L, 10L)).thenReturn(9L);
        assertEquals(9L, new EnrollmentRedissonFacade(service, redis, true).enroll(1L, 10L));
        var order = inOrder(service, course, student);
        order.verify(student).tryLock(10, TimeUnit.SECONDS);
        order.verify(service).validateForEnrollment(1L, 10L);
        order.verify(service).validateCourseCapacity(10L);
        order.verify(course).tryLock(anyLong(), eq(TimeUnit.NANOSECONDS));
        order.verify(service).completeEnrollment(1L, 10L);
        order.verify(course).isHeldByCurrentThread();
        order.verify(course).unlock();
        order.verify(student).isHeldByCurrentThread();
        order.verify(student).unlock();
    }

    @Test
    void redisFailureDoesNotLeakAdmissionPermitOrWriteWithoutLock() throws Exception {
        EnrollmentService service = mock(EnrollmentService.class);
        RedissonClient redis = mock(RedissonClient.class);
        RLock student = mock(RLock.class), course = mock(RLock.class);
        when(redis.getLock("lock:student:1")).thenReturn(student);
        when(redis.getLock("lock:course:10")).thenReturn(course);
        when(student.tryLock(10, TimeUnit.SECONDS)).thenReturn(true);
        when(student.isHeldByCurrentThread()).thenReturn(true);
        when(course.tryLock(anyLong(), eq(TimeUnit.NANOSECONDS)))
                .thenThrow(new RedisException("offline")).thenReturn(true);
        when(course.isHeldByCurrentThread()).thenReturn(true);
        when(service.completeEnrollment(1L, 10L)).thenReturn(9L);
        var facade = new EnrollmentRedissonFacade(service, redis, true);
        assertEquals("REDIS_UNAVAILABLE", assertThrows(EnrollmentException.class,
                () -> facade.enroll(1L, 10L)).getCode());
        verify(service, never()).completeEnrollment(anyLong(), anyLong());
        assertEquals(9L, facade.enroll(1L, 10L));
        verify(student, times(2)).unlock();
    }
}
