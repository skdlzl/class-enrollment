package com.jiyun.classenrollment.enrollment.application;

import com.jiyun.classenrollment.common.error.EnrollmentException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EnrollmentRedissonFacadeTest {

    @Mock
    private EnrollmentService enrollmentService;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock studentLock;

    @Mock
    private RLock courseLock;

    private EnrollmentRedissonFacade enrollmentRedissonFacade;

    @BeforeEach
    void setUp() {
        enrollmentRedissonFacade = new EnrollmentRedissonFacade(enrollmentService, redissonClient);

        when(redissonClient.getLock("lock:student:1")).thenReturn(studentLock);
        when(redissonClient.getLock("lock:course:10")).thenReturn(courseLock);
    }

    @Test
    void 학생락과_과목락을_획득한후_전체_수강신청을_실행한다() throws InterruptedException {
        when(studentLock.tryLock(5L, TimeUnit.SECONDS)).thenReturn(true);
        when(courseLock.tryLock(5L, TimeUnit.SECONDS)).thenReturn(true);
        when(studentLock.isHeldByCurrentThread()).thenReturn(true);
        when(courseLock.isHeldByCurrentThread()).thenReturn(true);
        when(enrollmentService.enroll(1L, 10L)).thenReturn(100L);

        Long enrollmentId = enrollmentRedissonFacade.enroll(1L, 10L);

        assertEquals(100L, enrollmentId);

        InOrder inOrder = inOrder(studentLock, courseLock, enrollmentService);
        inOrder.verify(studentLock).tryLock(5L, TimeUnit.SECONDS);
        inOrder.verify(courseLock).tryLock(5L, TimeUnit.SECONDS);
        inOrder.verify(enrollmentService).enroll(1L, 10L);

        verify(courseLock).unlock();
        verify(studentLock).unlock();
    }

    @Test
    void 과목락을_획득하지_못하면_신청하지_않고_학생락을_해제한다() throws InterruptedException {
        when(studentLock.tryLock(5L, TimeUnit.SECONDS)).thenReturn(true);
        when(courseLock.tryLock(5L, TimeUnit.SECONDS)).thenReturn(false);
        when(studentLock.isHeldByCurrentThread()).thenReturn(true);

        EnrollmentException exception = assertThrows(
                EnrollmentException.class,
                () -> enrollmentRedissonFacade.enroll(1L, 10L)
        );

        assertEquals("LOCK_ACQUISITION_TIMEOUT", exception.getCode());
        verify(enrollmentService, never()).enroll(1L, 10L);
        verify(courseLock, never()).unlock();
        verify(studentLock).unlock();
    }

    @Test
    void 학생락을_획득하지_못하면_과목락과_수강신청을_실행하지_않는다() throws InterruptedException {
        when(studentLock.tryLock(5L, TimeUnit.SECONDS)).thenReturn(false);

        EnrollmentException exception = assertThrows(
                EnrollmentException.class,
                () -> enrollmentRedissonFacade.enroll(1L, 10L)
        );

        assertEquals("LOCK_ACQUISITION_TIMEOUT", exception.getCode());
        verify(courseLock, never()).tryLock(5L, TimeUnit.SECONDS);
        verify(enrollmentService, never()).enroll(1L, 10L);
    }
}
