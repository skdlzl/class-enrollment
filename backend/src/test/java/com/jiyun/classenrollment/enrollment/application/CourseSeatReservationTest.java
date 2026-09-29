package com.jiyun.classenrollment.enrollment.application;

import com.jiyun.classenrollment.common.error.EnrollmentException;
import com.jiyun.classenrollment.course.domain.Course;
import com.jiyun.classenrollment.course.domain.CourseRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RedissonClient;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CourseSeatReservationTest {

    @Mock
    private CourseRepository courseRepository;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RPermitExpirableSemaphore semaphore;

    @Mock
    private RLock initializationLock;

    @Test
    void 초기화되지_않았으면_DB의_남은좌석으로_세마포어를_초기화한다() {
        Course course = mock(Course.class);
        when(redissonClient.getPermitExpirableSemaphore(
                "enrollment:course:10:seats"
        )).thenReturn(semaphore);
        when(redissonClient.getLock(
                "enrollment:course:10:seats:initialization"
        )).thenReturn(initializationLock);
        when(semaphore.isExists()).thenReturn(false, false);
        when(courseRepository.findById(10L)).thenReturn(Optional.of(course));
        when(course.getCapacity()).thenReturn(10);
        when(course.getEnrolledCount()).thenReturn(2);
        when(semaphore.tryAcquire()).thenReturn("permit-1");
        when(initializationLock.isHeldByCurrentThread()).thenReturn(true);

        CourseSeatReservation reservation =
                new CourseSeatReservation(courseRepository, redissonClient);

        String permitId = reservation.reserve(10L);

        assertEquals("permit-1", permitId);
        verify(initializationLock).lock();
        verify(semaphore).trySetPermits(8);
        verify(initializationLock).unlock();
    }

    @Test
    void 남은좌석이_없으면_즉시_정원마감_예외를_반환한다() {
        when(redissonClient.getPermitExpirableSemaphore(
                "enrollment:course:10:seats"
        )).thenReturn(semaphore);
        when(semaphore.isExists()).thenReturn(true);
        when(semaphore.tryAcquire()).thenReturn(null);

        CourseSeatReservation reservation =
                new CourseSeatReservation(courseRepository, redissonClient);

        EnrollmentException exception = assertThrows(
                EnrollmentException.class,
                () -> reservation.reserve(10L)
        );

        assertEquals("COURSE_FULL", exception.getCode());
    }
}
