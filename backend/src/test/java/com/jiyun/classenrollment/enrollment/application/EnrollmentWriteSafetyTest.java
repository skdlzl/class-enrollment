package com.jiyun.classenrollment.enrollment.application;

import com.jiyun.classenrollment.common.error.EnrollmentException;
import com.jiyun.classenrollment.course.domain.*;
import com.jiyun.classenrollment.enrollment.domain.*;
import com.jiyun.classenrollment.student.domain.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EnrollmentWriteSafetyTest {
    StudentRepository students;
    CourseRepository courses;
    EnrollmentRepository enrollments;
    EnrollmentService service;
    EntityManager em;
    Student student;
    Course course;
    EnrollmentValidationSummary summary;

    @BeforeEach void setup() {
        students = mock(StudentRepository.class); courses = mock(CourseRepository.class);
        enrollments = mock(EnrollmentRepository.class); em = mock(EntityManager.class);
        service = new EnrollmentService(students, courses, enrollments);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "entityManager", em);
        student = mock(Student.class); course = mock(Course.class);
        summary = mock(EnrollmentValidationSummary.class);
        when(students.findByIdForUpdate(1L)).thenReturn(Optional.of(student));
        when(student.getStatus()).thenReturn(StudentStatus.ACTIVE);
        when(student.getMaxCredits()).thenReturn(3);
        when(courses.findById(2L)).thenReturn(Optional.of(course));
        when(course.getCredits()).thenReturn(3);
        when(enrollments.findValidationSummary(1L, 2L)).thenReturn(summary);
    }

    Long write(CourseLockStrategy strategy) {
        return switch (strategy) {
            case REDIS -> service.completeEnrollment(1L, 2L);
            case PESSIMISTIC -> service.completeEnrollmentWithPessimisticLock(1L, 2L);
            case DB_ONLY -> service.enrollWithDatabaseLocks(1L, 2L);
        };
    }

    @ParameterizedTest @EnumSource(CourseLockStrategy.class)
    void revalidatesCreditsUnderStudentDbLockBeforeAnyWrite(CourseLockStrategy strategy) {
        when(summary.getCurrentCredits()).thenReturn(3);
        assertEquals("CREDIT_LIMIT_EXCEEDED", assertThrows(EnrollmentException.class,
                () -> write(strategy)).getCode());
        var order = inOrder(students, enrollments);
        order.verify(students).findByIdForUpdate(1L);
        order.verify(enrollments).findValidationSummary(1L, 2L);
        verify(enrollments, never()).save(any());
        verifyNoInteractions(em);
    }

    @ParameterizedTest @EnumSource(CourseLockStrategy.class)
    void locksStudentBeforeCourseAndChecksRefreshedCapacity(CourseLockStrategy strategy) {
        when(course.isFull()).thenReturn(false, true);
        assertEquals("COURSE_FULL", assertThrows(EnrollmentException.class,
                () -> write(strategy)).getCode());
        var order = inOrder(students, enrollments, em, course);
        order.verify(students).findByIdForUpdate(1L);
        order.verify(enrollments).findValidationSummary(1L, 2L);
        order.verify(course).isFull();
        order.verify(em).refresh(course, LockModeType.PESSIMISTIC_WRITE);
        order.verify(course).isFull();
        verify(enrollments, never()).save(any());
    }
}
