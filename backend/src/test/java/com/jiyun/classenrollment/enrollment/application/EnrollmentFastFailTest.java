package com.jiyun.classenrollment.enrollment.application;

import com.jiyun.classenrollment.common.error.EnrollmentException;
import com.jiyun.classenrollment.course.domain.Course;
import com.jiyun.classenrollment.course.domain.CourseRepository;
import com.jiyun.classenrollment.enrollment.domain.Enrollment;
import com.jiyun.classenrollment.enrollment.domain.EnrollmentRepository;
import com.jiyun.classenrollment.enrollment.domain.EnrollmentValidationSummary;
import com.jiyun.classenrollment.student.domain.Student;
import com.jiyun.classenrollment.student.domain.StudentRepository;
import com.jiyun.classenrollment.student.domain.StudentStatus;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.http.HttpStatus;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EnrollmentFastFailTest {

    private final StudentRepository students = mock(StudentRepository.class);
    private final CourseRepository courses = mock(CourseRepository.class);
    private final EnrollmentRepository enrollments = mock(EnrollmentRepository.class);
    private final Student student = mock(Student.class);
    private final Course course = mock(Course.class);

    private final EnrollmentService service =
            new EnrollmentService(students, courses, enrollments);

    private void givenValidStudent() {
        when(students.findById(1L)).thenReturn(Optional.of(student));
        when(courses.findById(10L)).thenReturn(Optional.of(course));
        when(student.getStatus()).thenReturn(StudentStatus.ACTIVE);
        when(student.getMaxCredits()).thenReturn(18);
        when(course.getCredits()).thenReturn(3);
        when(enrollments.findValidationSummary(1L, 10L))
                .thenReturn(mock(EnrollmentValidationSummary.class));
    }

    @Test
    void 마감된_과목은_사전검증에서_거절한다() {
        givenValidStudent();
        when(course.isFull()).thenReturn(true);

        EnrollmentException exception = assertThrows(
                EnrollmentException.class,
                () -> service.validateForEnrollment(1L, 10L)
        );

        assertEquals("COURSE_FULL", exception.getCode());
        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        verify(enrollments, never()).save(any(Enrollment.class));
        verify(course, never()).increaseEnrolledCount();
    }

    @Test
    void 사전검증후_마감되면_저장직전_재확인에서_거절한다() {
        givenValidStudent();
        when(course.isFull()).thenReturn(false, true);
        when(students.getReferenceById(1L)).thenReturn(student);

        assertDoesNotThrow(() -> service.validateForEnrollment(1L, 10L));

        EnrollmentException exception = assertThrows(
                EnrollmentException.class,
                () -> service.completeEnrollment(1L, 10L)
        );

        assertEquals("COURSE_FULL", exception.getCode());
        verify(courses, times(2)).findById(10L);
        verify(course, times(2)).isFull();
        verify(enrollments, never()).save(any(Enrollment.class));
        verify(course, never()).increaseEnrolledCount();
    }

    @Test
    void 사전마감이면_과목락을_시도하지_않고_학생락을_해제한다()
            throws InterruptedException {
        EnrollmentService mockedService = mock(EnrollmentService.class);
        RedissonClient redis = mock(RedissonClient.class);
        RLock studentLock = mock(RLock.class);
        RLock courseLock = mock(RLock.class);

        when(redis.getLock("lock:student:1")).thenReturn(studentLock);
        when(redis.getLock("lock:course:10")).thenReturn(courseLock);
        when(studentLock.tryLock(10L, TimeUnit.SECONDS)).thenReturn(true);
        when(studentLock.isHeldByCurrentThread()).thenReturn(true);

        doThrow(new EnrollmentException(
                "COURSE_FULL",
                "수강 정원이 마감되었습니다.",
                HttpStatus.CONFLICT
        )).when(mockedService).validateForEnrollment(1L, 10L);

        EnrollmentRedissonFacade facade =
                new EnrollmentRedissonFacade(mockedService, redis);

        EnrollmentException exception = assertThrows(
                EnrollmentException.class,
                () -> facade.enroll(1L, 10L)
        );

        assertEquals("COURSE_FULL", exception.getCode());
        verify(courseLock, never()).tryLock(anyLong(), eq(TimeUnit.NANOSECONDS));
        verify(mockedService, never()).completeEnrollment(1L, 10L);
        verify(courseLock, never()).unlock();
        verify(studentLock).unlock();
    }
}
