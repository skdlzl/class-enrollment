package com.jiyun.classenrollment.enrollment.application;

import com.jiyun.classenrollment.common.error.EnrollmentException;
import com.jiyun.classenrollment.course.domain.CourseRepository;
import com.jiyun.classenrollment.enrollment.domain.EnrollmentRepository;
import com.jiyun.classenrollment.student.domain.StudentRepository;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.springframework.http.HttpStatus;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EnrollmentCourseLockPollingTest {
    private final EnrollmentService service = mock(EnrollmentService.class);
    private final RedissonClient redis = mock(RedissonClient.class);
    private final RLock studentLock = mock(RLock.class);
    private final RLock courseLock = mock(RLock.class);
    private final EnrollmentRedissonFacade facade = new EnrollmentRedissonFacade(service, redis);

    private void givenStudentLock() throws InterruptedException {
        when(redis.getLock("lock:student:1")).thenReturn(studentLock);
        when(redis.getLock("lock:course:10")).thenReturn(courseLock);
        when(studentLock.tryLock(10L, TimeUnit.SECONDS)).thenReturn(true);
        when(studentLock.isHeldByCurrentThread()).thenReturn(true);
    }

    @Test
    void 대기중_마감되면_저장없이_거절하고_학생락을_해제한다() throws Exception {
        givenStudentLock();
        when(courseLock.tryLock(anyLong(), eq(TimeUnit.NANOSECONDS))).thenReturn(false);
        doThrow(new EnrollmentException("COURSE_FULL", "마감", HttpStatus.CONFLICT))
                .when(service).validateCourseCapacity(10L);
        EnrollmentException exception = assertThrows(EnrollmentException.class,
                () -> facade.enroll(1L, 10L));
        assertEquals("COURSE_FULL", exception.getCode());
        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        verify(courseLock, times(1)).tryLock(anyLong(), eq(TimeUnit.NANOSECONDS));
        verify(service, never()).completeEnrollment(anyLong(), anyLong());
        verify(courseLock, never()).unlock();
        verify(studentLock).unlock();
    }

    @Test
    void 자리가_남으면_재시도하고_획득후_저장한다() throws Exception {
        givenStudentLock();
        when(courseLock.tryLock(anyLong(), eq(TimeUnit.NANOSECONDS))).thenReturn(false, true);
        when(courseLock.isHeldByCurrentThread()).thenReturn(true);
        when(service.completeEnrollment(1L, 10L)).thenReturn(100L);
        assertEquals(100L, facade.enroll(1L, 10L));
        var order = inOrder(service, courseLock);
        order.verify(service).validateForEnrollment(1L, 10L);
        order.verify(courseLock).tryLock(anyLong(), eq(TimeUnit.NANOSECONDS));
        order.verify(service).validateCourseCapacity(10L);
        order.verify(courseLock).tryLock(anyLong(), eq(TimeUnit.NANOSECONDS));
        order.verify(service).completeEnrollment(1L, 10L);
        order.verify(courseLock).unlock();
        verify(studentLock).unlock();
    }

    @Test
    void 재시도와_DB조회가_같은_10초_예산을_사용한다() throws Exception {
        AtomicLong clock = new AtomicLong();
        when(courseLock.tryLock(anyLong(), eq(TimeUnit.NANOSECONDS))).thenAnswer(call -> {
            clock.addAndGet((long) call.getArgument(0));
            return false;
        });
        // Simulate time consumed by the DB query, without sleeping.
        doAnswer(call -> { clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(200)); return null; })
                .when(service).validateCourseCapacity(10L);
        assertFalse(facade.acquireCourseLock(courseLock, 10L, clock::get));
        var waits = org.mockito.ArgumentCaptor.forClass(Long.class);
        verify(courseLock, times(15)).tryLock(waits.capture(), eq(TimeUnit.NANOSECONDS));
        assertEquals(TimeUnit.MILLISECONDS.toNanos(200), waits.getValue());
        assertTrue(waits.getAllValues().stream().allMatch(n -> n > 0 && n <= 500_000_000L));
        verify(service, times(15)).validateCourseCapacity(10L);
        verify(service, never()).completeEnrollment(anyLong(), anyLong());
    }

    @Test
    void Redis장애시_재시도하지_않고_학생락을_해제한다() throws Exception {
        givenStudentLock();
        when(courseLock.tryLock(anyLong(), eq(TimeUnit.NANOSECONDS)))
                .thenThrow(new RedisException("unavailable"));
        EnrollmentException exception = assertThrows(EnrollmentException.class,
                () -> facade.enroll(1L, 10L));
        assertEquals("REDIS_UNAVAILABLE", exception.getCode());
        verify(service, never()).validateCourseCapacity(anyLong());
        verify(service, never()).completeEnrollment(anyLong(), anyLong());
        verify(studentLock).unlock();
    }

    @Test
    void 인터럽트시_학생락을_해제하고_중단상태를_보존한다() throws Exception {
        givenStudentLock();
        when(courseLock.tryLock(anyLong(), eq(TimeUnit.NANOSECONDS)))
                .thenThrow(new InterruptedException());
        try {
            assertThrows(IllegalStateException.class, () -> facade.enroll(1L, 10L));
            assertTrue(Thread.currentThread().isInterrupted());
            verify(studentLock).unlock();
            verify(service, never()).completeEnrollment(anyLong(), anyLong());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void 대기중_정원검사는_과목객체대신_스칼라조회로_판단한다() {
        CourseRepository courses = mock(CourseRepository.class);
        EnrollmentService actual = new EnrollmentService(mock(StudentRepository.class),
                courses, mock(EnrollmentRepository.class));
        when(courses.findFullStatusById(10L)).thenReturn(Optional.of(false), Optional.of(true));
        assertDoesNotThrow(() -> actual.validateCourseCapacity(10L));
        EnrollmentException exception = assertThrows(EnrollmentException.class,
                () -> actual.validateCourseCapacity(10L));
        assertEquals("COURSE_FULL", exception.getCode());
        verify(courses, never()).findById(anyLong());
    }

    @Test
    void 대기중_과목이_없으면_404로_거절한다() {
        CourseRepository courses = mock(CourseRepository.class);
        EnrollmentService actual = new EnrollmentService(mock(StudentRepository.class),
                courses, mock(EnrollmentRepository.class));
        when(courses.findFullStatusById(10L)).thenReturn(Optional.empty());
        EnrollmentException exception = assertThrows(EnrollmentException.class,
                () -> actual.validateCourseCapacity(10L));
        assertEquals("COURSE_NOT_FOUND", exception.getCode());
        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
    }
}
