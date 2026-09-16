package com.jiyun.classenrollment.enrollment.application;

import com.jiyun.classenrollment.common.error.EnrollmentException;
import com.jiyun.classenrollment.course.domain.Course;
import com.jiyun.classenrollment.course.domain.CourseRepository;
import com.jiyun.classenrollment.enrollment.domain.Enrollment;
import com.jiyun.classenrollment.enrollment.domain.EnrollmentRepository;
import com.jiyun.classenrollment.student.domain.Student;
import com.jiyun.classenrollment.student.domain.StudentRepository;
import com.jiyun.classenrollment.student.domain.StudentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EnrollmentServiceTest {

    @Mock
    private StudentRepository studentRepository;

    @Mock
    private CourseRepository courseRepository;

    @Mock
    private EnrollmentRepository enrollmentRepository;

    @InjectMocks
    private EnrollmentService enrollmentService;

    @Test
    void 최대학점을_초과하면_신청하지_않는다() {
        // 학생 1은 이미 18학점을 신청했고, 최대 신청 가능 학점도 18학점이다.
        Long studentId = 1L;
        Long courseId = 10L;
        Student student = mock(Student.class);
        Course course = mock(Course.class);

        when(studentRepository.findById(studentId)).thenReturn(Optional.of(student));
        when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
        when(student.getStatus()).thenReturn(StudentStatus.ACTIVE);
        when(student.getId()).thenReturn(studentId);
        when(student.getMaxCredits()).thenReturn(18);
        when(course.getCredits()).thenReturn(3);
        when(enrollmentRepository.sumCreditsByStudentId(studentId)).thenReturn(18);

        // 신규 3학점을 더하면 21학점이 되므로 서비스는 저장 전에 거절해야 한다.
        EnrollmentException exception = assertThrows(EnrollmentException.class,
                () -> enrollmentService.enroll(studentId, courseId));

        assertEquals("CREDIT_LIMIT_EXCEEDED", exception.getCode());
        verify(enrollmentRepository, never()).save(any(Enrollment.class));
    }

    @Test
    void 동일과목을_중복신청하면_실패한다() {
        Long studentId = 1L;
        Long courseId = 10L;
        Student student = mock(Student.class);
        Course course = mock(Course.class);

        when(studentRepository.findById(studentId)).thenReturn(Optional.of(student));
        when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
        when(student.getStatus()).thenReturn(StudentStatus.ACTIVE);
        when(enrollmentRepository.existsByStudentIdAndCourseId(studentId, courseId)).thenReturn(true);

        // 이미 신청된 과목이 존재하면 중복신청이므로 저장하지 않는다.
        EnrollmentException exception = assertThrows(EnrollmentException.class,
                () -> enrollmentService.enroll(studentId, courseId));

        assertEquals("DUPLICATE_ENROLLMENT", exception.getCode());
        verify(enrollmentRepository, never()).save(any(Enrollment.class));

    }
}
