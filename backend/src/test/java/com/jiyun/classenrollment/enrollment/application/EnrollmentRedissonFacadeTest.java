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

    private EnrollmentRedissonFacade enrollmentRedissonFacade;

    @BeforeEach
    void setUp() {
        enrollmentRedissonFacade = new EnrollmentRedissonFacade(
                enrollmentService,
                redissonClient
        );

        when(redissonClient.getLock("lock:student:1")).thenReturn(studentLock);
    }

    @Test
    void 학생락을_획득한후_수강신청을_실행한다() throws InterruptedException {
        when(studentLock.tryLock(10L, TimeUnit.SECONDS)).thenReturn(true);
        when(studentLock.isHeldByCurrentThread()).thenReturn(true);
        when(enrollmentService.enroll(1L, 10L)).thenReturn(100L);

        Long enrollmentId = enrollmentRedissonFacade.enroll(1L, 10L);

        assertEquals(100L, enrollmentId);

        InOrder inOrder = inOrder(studentLock, enrollmentService);
        inOrder.verify(studentLock).tryLock(10L, TimeUnit.SECONDS);
        inOrder.verify(enrollmentService).enroll(1L, 10L);
        inOrder.verify(studentLock).isHeldByCurrentThread();
        inOrder.verify(studentLock).unlock();
    }

    @Test
    void 학생락을_획득하지_못하면_수강신청을_실행하지_않는다()
            throws InterruptedException {
        when(studentLock.tryLock(10L, TimeUnit.SECONDS)).thenReturn(false);

        EnrollmentException exception = assertThrows(
                EnrollmentException.class,
                () -> enrollmentRedissonFacade.enroll(1L, 10L)
        );

        assertEquals("LOCK_ACQUISITION_TIMEOUT", exception.getCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatus());
        verify(enrollmentService, never()).enroll(1L, 10L);
        verify(studentLock, never()).unlock();
    }

    @Test
    void 서비스에서_업무예외가_발생해도_학생락을_해제한다()
            throws InterruptedException {
        EnrollmentException courseFull = new EnrollmentException(
                "COURSE_FULL",
                "수강 정원이 마감되었습니다.",
                HttpStatus.CONFLICT
        );

        when(studentLock.tryLock(10L, TimeUnit.SECONDS)).thenReturn(true);
        when(studentLock.isHeldByCurrentThread()).thenReturn(true);
        when(enrollmentService.enroll(1L, 10L)).thenThrow(courseFull);

        EnrollmentException exception = assertThrows(
                EnrollmentException.class,
                () -> enrollmentRedissonFacade.enroll(1L, 10L)
        );

        assertEquals("COURSE_FULL", exception.getCode());
        verify(studentLock).unlock();
    }

    @Test
    void Redis_연결이_끊기면_503_예외로_변환하고_수강신청을_실행하지_않는다()
            throws InterruptedException {
        RedisException redisException = new RedisException(
                "Redis connection refused"
        );

        when(studentLock.tryLock(10L, TimeUnit.SECONDS))
                .thenThrow(redisException);

        EnrollmentException exception = assertThrows(
                EnrollmentException.class,
                () -> enrollmentRedissonFacade.enroll(1L, 10L)
        );

        assertEquals("REDIS_UNAVAILABLE", exception.getCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatus());
        assertInstanceOf(RedisException.class, exception.getCause());
        verify(enrollmentService, never()).enroll(1L, 10L);
        verify(studentLock, never()).unlock();
    }
}
