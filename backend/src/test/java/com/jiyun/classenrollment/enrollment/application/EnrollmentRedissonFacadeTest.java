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
import org.redisson.client.RedisException;
import org.springframework.http.HttpStatus;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
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
        enrollmentRedissonFacade = new EnrollmentRedissonFacade(
                enrollmentService,
                redissonClient
        );

        when(redissonClient.getLock("lock:student:1"))
                .thenReturn(studentLock);
        when(redissonClient.getLock("lock:course:10"))
                .thenReturn(courseLock);
    }

    @Test
    void 학생검증을_마친후_과목락을_획득하고_저장한다()
            throws InterruptedException {
        when(studentLock.tryLock(10L, TimeUnit.SECONDS)).thenReturn(true);
        when(courseLock.tryLock(10L, TimeUnit.SECONDS)).thenReturn(true);
        when(studentLock.isHeldByCurrentThread()).thenReturn(true);
        when(courseLock.isHeldByCurrentThread()).thenReturn(true);
        when(enrollmentService.completeEnrollment(1L, 10L)).thenReturn(100L);

        Long enrollmentId = enrollmentRedissonFacade.enroll(1L, 10L);

        assertEquals(100L, enrollmentId);

        InOrder inOrder = inOrder(
                studentLock,
                courseLock,
                enrollmentService
        );
        inOrder.verify(studentLock).tryLock(10L, TimeUnit.SECONDS);
        inOrder.verify(enrollmentService).validateForEnrollment(1L, 10L);
        inOrder.verify(courseLock).tryLock(10L, TimeUnit.SECONDS);
        inOrder.verify(enrollmentService).completeEnrollment(1L, 10L);

        verify(courseLock).unlock();
        verify(studentLock).unlock();
    }

    @Test
    void 과목락을_얻지_못해도_학생검증은_과목락_밖에서_완료된다()
            throws InterruptedException {
        when(studentLock.tryLock(10L, TimeUnit.SECONDS)).thenReturn(true);
        when(courseLock.tryLock(10L, TimeUnit.SECONDS)).thenReturn(false);
        when(studentLock.isHeldByCurrentThread()).thenReturn(true);

        EnrollmentException exception = assertThrows(
                EnrollmentException.class,
                () -> enrollmentRedissonFacade.enroll(1L, 10L)
        );

        assertEquals("LOCK_ACQUISITION_TIMEOUT", exception.getCode());
        verify(enrollmentService).validateForEnrollment(1L, 10L);
        verify(enrollmentService, never()).completeEnrollment(1L, 10L);
        verify(courseLock, never()).unlock();
        verify(studentLock).unlock();
    }

    @Test
    void 학생검증에_실패하면_과목락을_시도하지_않는다()
            throws InterruptedException {
        EnrollmentException duplicate = new EnrollmentException(
                "DUPLICATE_ENROLLMENT",
                "이미 신청한 과목입니다.",
                HttpStatus.CONFLICT
        );

        when(studentLock.tryLock(10L, TimeUnit.SECONDS)).thenReturn(true);
        when(studentLock.isHeldByCurrentThread()).thenReturn(true);
        doThrow(duplicate)
                .when(enrollmentService)
                .validateForEnrollment(1L, 10L);

        EnrollmentException exception = assertThrows(
                EnrollmentException.class,
                () -> enrollmentRedissonFacade.enroll(1L, 10L)
        );

        assertEquals("DUPLICATE_ENROLLMENT", exception.getCode());
        verify(courseLock, never()).tryLock(10L, TimeUnit.SECONDS);
        verify(enrollmentService, never()).completeEnrollment(1L, 10L);
        verify(studentLock).unlock();
    }

    @Test
    void 학생락을_획득하지_못하면_검증과_과목락을_실행하지_않는다()
            throws InterruptedException {
        when(studentLock.tryLock(10L, TimeUnit.SECONDS)).thenReturn(false);

        EnrollmentException exception = assertThrows(
                EnrollmentException.class,
                () -> enrollmentRedissonFacade.enroll(1L, 10L)
        );

        assertEquals("LOCK_ACQUISITION_TIMEOUT", exception.getCode());
        verify(enrollmentService, never()).validateForEnrollment(1L, 10L);
        verify(courseLock, never()).tryLock(10L, TimeUnit.SECONDS);
        verify(enrollmentService, never()).completeEnrollment(1L, 10L);
    }

    @Test
    void Redis_연결이_끊기면_503으로_변환한다()
            throws InterruptedException {
        RedisException redisException =
                new RedisException("Redis connection refused");

        when(studentLock.tryLock(10L, TimeUnit.SECONDS))
                .thenThrow(redisException);

        EnrollmentException exception = assertThrows(
                EnrollmentException.class,
                () -> enrollmentRedissonFacade.enroll(1L, 10L)
        );

        assertEquals("REDIS_UNAVAILABLE", exception.getCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatus());
        assertInstanceOf(RedisException.class, exception.getCause());
        verify(enrollmentService, never()).validateForEnrollment(1L, 10L);
        verify(enrollmentService, never()).completeEnrollment(1L, 10L);
    }
}
