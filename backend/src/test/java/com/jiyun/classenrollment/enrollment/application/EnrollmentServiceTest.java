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
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
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
    void 최대학점을_초과하면_좌석을_확보하지_않는다() {
        Long studentId = 1L;
        Long courseId = 10L;
        Student student = activeStudent(studentId, 18);
        Course course = courseWithCredits(3);
        EnrollmentValidationSummary validation = validation(false, 18, false);

        givenStudentAndCourse(studentId, courseId, student, course);
        when(enrollmentRepository.findValidationSummary(studentId, courseId))
                .thenReturn(validation);

        EnrollmentException exception = assertThrows(
                EnrollmentException.class,
                () -> enrollmentService.enroll(studentId, courseId)
        );

        assertEquals("CREDIT_LIMIT_EXCEEDED", exception.getCode());
        verify(courseRepository, never()).increaseEnrolledCountIfAvailable(courseId);
        verify(enrollmentRepository, never()).save(any(Enrollment.class));
    }

    @Test
    void 동일과목을_중복신청하면_좌석을_확보하지_않는다() {
        Long studentId = 1L;
        Long courseId = 10L;
        Student student = activeStudent(studentId, 18);
        Course course = courseWithCredits(3);
        EnrollmentValidationSummary validation = validation(true, 0, false);

        givenStudentAndCourse(studentId, courseId, student, course);
        when(enrollmentRepository.findValidationSummary(studentId, courseId))
                .thenReturn(validation);

        EnrollmentException exception = assertThrows(
                EnrollmentException.class,
                () -> enrollmentService.enroll(studentId, courseId)
        );

        assertEquals("DUPLICATE_ENROLLMENT", exception.getCode());
        verify(courseRepository, never()).increaseEnrolledCountIfAvailable(courseId);
        verify(enrollmentRepository, never()).save(any(Enrollment.class));
    }

    @ParameterizedTest
    @EnumSource(value = StudentStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    void 재학상태가_아니면_좌석을_확보하지_않는다(StudentStatus status) {
        Long studentId = 1L;
        Long courseId = 10L;
        Student student = mock(Student.class);
        Course course = mock(Course.class);

        when(studentRepository.findById(studentId)).thenReturn(Optional.of(student));
        when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
        when(student.getStatus()).thenReturn(status);

        EnrollmentException exception = assertThrows(
                EnrollmentException.class,
                () -> enrollmentService.enroll(studentId, courseId)
        );

        assertEquals("STUDENT_NOT_ACTIVE", exception.getCode());
        verify(enrollmentRepository, never()).findValidationSummary(studentId, courseId);
        verify(courseRepository, never()).increaseEnrolledCountIfAvailable(courseId);
        verify(enrollmentRepository, never()).save(any(Enrollment.class));
    }

    @Test
    void 학생이_존재하지_않으면_수강신청에_실패한다() {
        Long studentId = 1L;
        Long courseId = 10L;
        when(studentRepository.findById(studentId)).thenReturn(Optional.empty());

        EnrollmentException exception = assertThrows(
                EnrollmentException.class,
                () -> enrollmentService.enroll(studentId, courseId)
        );

        assertEquals("STUDENT_NOT_FOUND", exception.getCode());
        verify(courseRepository, never()).increaseEnrolledCountIfAvailable(courseId);
        verify(enrollmentRepository, never()).save(any(Enrollment.class));
    }

    @Test
    void 과목이_존재하지_않으면_수강신청에_실패한다() {
        Long studentId = 1L;
        Long courseId = 10L;
        Student student = mock(Student.class);

        when(studentRepository.findById(studentId)).thenReturn(Optional.of(student));
        when(courseRepository.findById(courseId)).thenReturn(Optional.empty());

        EnrollmentException exception = assertThrows(
                EnrollmentException.class,
                () -> enrollmentService.enroll(studentId, courseId)
        );

        assertEquals("COURSE_NOT_FOUND", exception.getCode());
        verify(courseRepository, never()).increaseEnrolledCountIfAvailable(courseId);
        verify(enrollmentRepository, never()).save(any(Enrollment.class));
    }

    @Test
    void 신청후_학점이_최대학점과_같으면_좌석을_확보하고_저장한다() {
        Long studentId = 1L;
        Long courseId = 10L;
        Student student = activeStudent(studentId, 18);
        Course course = courseWithCredits(3);
        Enrollment savedEnrollment = mock(Enrollment.class);
        EnrollmentValidationSummary validation = validation(false, 15, false);

        givenStudentAndCourse(studentId, courseId, student, course);
        when(enrollmentRepository.findValidationSummary(studentId, courseId))
                .thenReturn(validation);
        when(courseRepository.increaseEnrolledCountIfAvailable(courseId)).thenReturn(1);
        when(enrollmentRepository.save(any(Enrollment.class))).thenReturn(savedEnrollment);
        when(savedEnrollment.getId()).thenReturn(100L);

        Long enrollmentId = enrollmentService.enroll(studentId, courseId);

        assertEquals(100L, enrollmentId);
        verify(courseRepository).increaseEnrolledCountIfAvailable(courseId);
        verify(enrollmentRepository).save(any(Enrollment.class));
    }

    @Test
    void 기존과목과_시간이_겹치면_좌석을_확보하지_않는다() {
        Long studentId = 1L;
        Long courseId = 10L;
        Student student = activeStudent(studentId, 18);
        Course course = courseWithCredits(3);
        EnrollmentValidationSummary validation = validation(false, 12, true);

        givenStudentAndCourse(studentId, courseId, student, course);
        when(enrollmentRepository.findValidationSummary(studentId, courseId))
                .thenReturn(validation);

        EnrollmentException exception = assertThrows(
                EnrollmentException.class,
                () -> enrollmentService.enroll(studentId, courseId)
        );

        assertEquals("SCHEDULE_CONFLICT", exception.getCode());
        verify(courseRepository, never()).increaseEnrolledCountIfAvailable(courseId);
        verify(enrollmentRepository, never()).save(any(Enrollment.class));
    }

    @Test
    void 시간표_충돌이_없으면_좌석을_확보하고_저장한다() {
        Long studentId = 1L;
        Long courseId = 10L;
        Student student = activeStudent(studentId, 18);
        Course course = courseWithCredits(3);
        Enrollment savedEnrollment = mock(Enrollment.class);
        EnrollmentValidationSummary validation = validation(false, 12, false);

        givenStudentAndCourse(studentId, courseId, student, course);
        when(enrollmentRepository.findValidationSummary(studentId, courseId))
                .thenReturn(validation);
        when(courseRepository.increaseEnrolledCountIfAvailable(courseId)).thenReturn(1);
        when(enrollmentRepository.save(any(Enrollment.class))).thenReturn(savedEnrollment);
        when(savedEnrollment.getId()).thenReturn(100L);

        Long enrollmentId = enrollmentService.enroll(studentId, courseId);

        assertEquals(100L, enrollmentId);
        verify(courseRepository).increaseEnrolledCountIfAvailable(courseId);
        verify(enrollmentRepository).save(any(Enrollment.class));
    }

    @Test
    void 조건부_UPDATE가_0건이면_정원마감으로_처리한다() {
        Long studentId = 1L;
        Long courseId = 10L;
        Student student = activeStudent(studentId, 18);
        Course course = courseWithCredits(3);
        EnrollmentValidationSummary validation = validation(false, 15, false);

        givenStudentAndCourse(studentId, courseId, student, course);
        when(enrollmentRepository.findValidationSummary(studentId, courseId))
                .thenReturn(validation);
        when(courseRepository.increaseEnrolledCountIfAvailable(courseId)).thenReturn(0);

        EnrollmentException exception = assertThrows(
                EnrollmentException.class,
                () -> enrollmentService.enroll(studentId, courseId)
        );

        assertEquals("COURSE_FULL", exception.getCode());
        verify(courseRepository).increaseEnrolledCountIfAvailable(courseId);
        verify(enrollmentRepository, never()).save(any(Enrollment.class));
    }

    private void givenStudentAndCourse(
            Long studentId,
            Long courseId,
            Student student,
            Course course
    ) {
        when(studentRepository.findById(studentId)).thenReturn(Optional.of(student));
        when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
    }

    private Student activeStudent(Long studentId, int maxCredits) {
        Student student = mock(Student.class);
        when(student.getStatus()).thenReturn(StudentStatus.ACTIVE);
        lenient().when(student.getId()).thenReturn(studentId);
        lenient().when(student.getMaxCredits()).thenReturn(maxCredits);
        return student;
    }

    private Course courseWithCredits(int credits) {
        Course course = mock(Course.class);
        lenient().when(course.getCredits()).thenReturn(credits);
        return course;
    }

    private EnrollmentValidationSummary validation(
            boolean duplicate,
            int currentCredits,
            boolean scheduleConflict
    ) {
        EnrollmentValidationSummary validation = mock(EnrollmentValidationSummary.class);
        lenient().when(validation.getDuplicateEnrollment())
                .thenReturn(duplicate ? 1L : 0L);
        lenient().when(validation.getCurrentCredits()).thenReturn(currentCredits);
        lenient().when(validation.getScheduleConflict())
                .thenReturn(scheduleConflict ? 1L : 0L);
        return validation;
    }
}
