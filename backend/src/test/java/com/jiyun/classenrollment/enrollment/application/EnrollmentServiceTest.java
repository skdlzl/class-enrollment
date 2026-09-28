package com.jiyun.classenrollment.enrollment.application;

import com.jiyun.classenrollment.common.error.EnrollmentException;
import com.jiyun.classenrollment.course.domain.Course;
import com.jiyun.classenrollment.course.domain.CourseRepository;
import com.jiyun.classenrollment.course.domain.CourseSchedule;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
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

    @ParameterizedTest
    @EnumSource(
            value = StudentStatus.class,
            names = "ACTIVE",
            mode = EnumSource.Mode.EXCLUDE
    )
    void 재학상태가_아니면_수강신청에_실패한다(StudentStatus status) {
        // Given: 학생과 과목은 존재한다.
        Long studentId = 1L;
        Long courseId = 10L;
        Student student = mock(Student.class);
        Course course = mock(Course.class);

        when(studentRepository.findById(studentId)).thenReturn(Optional.of(student));
        when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));

        // 테스트가 실행될 때마다 LEAVE, GRADUATED가 차례로 들어온다.
        when(student.getStatus()).thenReturn(status);

        // When: 재학 상태가 아닌 학생이 수강신청한다.
        EnrollmentException exception = assertThrows(
                EnrollmentException.class,
                () -> enrollmentService.enroll(studentId, courseId)
        );

        // Then: 학생 상태 오류로 거절되고 저장되지 않는다.
        assertEquals("STUDENT_NOT_ACTIVE", exception.getCode());
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
        verify(enrollmentRepository, never()).save(any(Enrollment.class));
    }

    @Test
    void 과목이_존재하지_않으면_수강신청에_실패한다() {
        Long studentId = 1L;
        Long courseId = 10L;

        Student student = mock(Student.class);
        Course course = mock(Course.class);

        when(studentRepository.findById(studentId)).thenReturn(Optional.of(student));
        when(courseRepository.findById(courseId)).thenReturn(Optional.empty());

        EnrollmentException exception = assertThrows(
                EnrollmentException.class,
                () -> enrollmentService.enroll(studentId, courseId)
        );

        assertEquals("COURSE_NOT_FOUND", exception.getCode());
        verify(enrollmentRepository, never()).save(any(Enrollment.class));
    }

    @Test
    void 신청후_학점이_최대학점과_같으면_성공한다() {
        Long studentId = 1L;
        Long courseId = 10L;

        Student student = mock(Student.class);
        Course course = mock(Course.class);
        Enrollment savedEnrollment = mock(Enrollment.class);

        when(studentRepository.findById(studentId)).thenReturn(Optional.of(student));
        when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
        when(student.getStatus()).thenReturn(StudentStatus.ACTIVE);
        when(student.getId()).thenReturn(studentId);
        when(student.getMaxCredits()).thenReturn(18);
        when(course.getCredits()).thenReturn(3);
        when(enrollmentRepository.sumCreditsByStudentId(studentId)).thenReturn(15);

        when(courseRepository.findSchedulesByCourseId(courseId)).thenReturn(List.of());
        when(course.isFull()).thenReturn(false);
        when(enrollmentRepository.save(any(Enrollment.class))).thenReturn(savedEnrollment);
        when(savedEnrollment.getId()).thenReturn(100L);

        // 현재 15학점에서 3학점을 신청하면 최대 학점인 18학점과 같으므로 신청할 수 있다.
        Long enrollmentId = enrollmentService.enroll(studentId, courseId);

        assertEquals(100L, enrollmentId);
        verify(enrollmentRepository).save(any(Enrollment.class));
        verify(course).increaseEnrolledCount();
    }

    @Test
    void 기존과목과_시간이_겹치면_수강신청에_실패한다() {
        Long studentId = 1L;
        Long courseId = 10L;

        Student student = mock(Student.class);
        Course course = mock(Course.class);
        CourseSchedule schedule = mock(CourseSchedule.class);

        when(studentRepository.findById(studentId)).thenReturn(Optional.of(student));

        when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));

        when(student.getStatus()).thenReturn(StudentStatus.ACTIVE);
        when(student.getId()).thenReturn(studentId);
        when(student.getMaxCredits()).thenReturn(18);

        // 신규 과목은 3학점이고 현재 신청 학점은 12학점이므로 학점 검사를 통과한다.
        when(course.getCredits()).thenReturn(3);
        when(enrollmentRepository.sumCreditsByStudentId(studentId)).thenReturn(12);

        // 신규 과목은 월요일 10시부터 12시까지 진행된다.
        when(schedule.getDayOfWeek()).thenReturn(DayOfWeek.MONDAY);
        when(schedule.getStartTime()).thenReturn(LocalTime.of(10, 0));
        when(schedule.getEndTime()).thenReturn(LocalTime.of(12, 0));

        when(courseRepository.findSchedulesByCourseId(courseId)).thenReturn(List.of(schedule));

        // 해당 시간과 겹치는 기존 신청 과목이 1개 존재한다.
        when(enrollmentRepository.countScheduleConflicts(
                studentId,
                DayOfWeek.MONDAY,
                LocalTime.of(10, 0),
                LocalTime.of(12, 0)
        )).thenReturn(1L);

        EnrollmentException exception = assertThrows(
                EnrollmentException.class,
                () -> enrollmentService.enroll(studentId, courseId)
        );

        assertEquals("SCHEDULE_CONFLICT", exception.getCode());
        verify(enrollmentRepository, never()).save(any(Enrollment.class));
    }

    @Test
    void 시간표_충돌건수가_0이면_수강신청에_성공한다() {
        Long studentId = 1L;
        Long courseId = 10L;

        Student student = mock(Student.class);
        Course course = mock(Course.class);
        CourseSchedule schedule = mock(CourseSchedule.class);
        Enrollment savedEnrollment = mock(Enrollment.class);

        // 학생과 과목이 존재한다.
        when(studentRepository.findById(studentId)).thenReturn(Optional.of(student));
        when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));

        // 학생 상태와 학점 조건을 통과한다.
        when(student.getStatus()).thenReturn(StudentStatus.ACTIVE);
        when(student.getId()).thenReturn(studentId);
        when(student.getMaxCredits()).thenReturn(18);
        when(course.getCredits()).thenReturn(3);
        when(enrollmentRepository.sumCreditsByStudentId(studentId)).thenReturn(12);

        // 신규 과목은 월요일 10시부터 12시까지 진행된다.
        when(schedule.getDayOfWeek()).thenReturn(DayOfWeek.MONDAY);
        when(schedule.getStartTime()).thenReturn(LocalTime.of(10, 0));
        when(schedule.getEndTime()).thenReturn(LocalTime.of(12, 0));

        when(courseRepository.findSchedulesByCourseId(courseId)).thenReturn(List.of(schedule));

        // 신규 과목 시간과 겹치는 기존 신청 과목이 없다.
        when(enrollmentRepository.countScheduleConflicts(
                studentId,
                DayOfWeek.MONDAY,
                LocalTime.of(10, 0),
                LocalTime.of(12, 0)
        )).thenReturn(0L);

        // 정원도 남아 있다.
        when(course.isFull()).thenReturn(false);

        // 저장된 수강신청의 ID는 100이다.
        when(enrollmentRepository.save(any(Enrollment.class)))
                .thenReturn(savedEnrollment);
        when(savedEnrollment.getId()).thenReturn(100L);

        // 수강신청을 실행한다.
        Long enrollmentId = enrollmentService.enroll(studentId, courseId);

        // 정상적으로 저장하고 저장된 신청 ID를 반환한다.
        assertEquals(100L, enrollmentId);
        verify(enrollmentRepository).save(any(Enrollment.class));
        verify(course).increaseEnrolledCount();
    }

    @Test
    void 수강정원이_가득차면_수강신청에_실패한다() {
        Long studentId = 1L;
        Long courseId = 10L;

        Student student = mock(Student.class);
        Course course = mock(Course.class);
        Enrollment savedEnrollment = mock(Enrollment.class);

        when(studentRepository.findById(studentId)).thenReturn(Optional.of(student));
        when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
        when(student.getStatus()).thenReturn(StudentStatus.ACTIVE);
        when(student.getId()).thenReturn(studentId);
        when(student.getMaxCredits()).thenReturn(18);
        when(course.getCredits()).thenReturn(3);
        when(enrollmentRepository.sumCreditsByStudentId(studentId)).thenReturn(15);

        when(courseRepository.findSchedulesByCourseId(courseId)).thenReturn(List.of());
        when(course.isFull()).thenReturn(true);

        EnrollmentException exception = assertThrows(
                EnrollmentException.class,
                () -> enrollmentService.enroll(studentId, courseId)
        );

        assertEquals("COURSE_FULL", exception.getCode());
        verify(enrollmentRepository, never()).save(any(Enrollment.class));
    }



    @Test
    void 학생조건_사전검증에서는_정원확인과_저장을_하지_않는다() {
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
        when(enrollmentRepository.sumCreditsByStudentId(studentId)).thenReturn(12);
        when(courseRepository.findSchedulesByCourseId(courseId)).thenReturn(List.of());

        enrollmentService.validateStudentConditions(studentId, courseId);

        verify(course, never()).isFull();
        verify(enrollmentRepository, never()).save(any(Enrollment.class));
        verify(course, never()).increaseEnrolledCount();
    }

    @Test
    void 학생검증후_신청에서는_정원확인과_저장만_수행한다() {
        Long studentId = 1L;
        Long courseId = 10L;
        Student student = mock(Student.class);
        Course course = mock(Course.class);
        Enrollment savedEnrollment = mock(Enrollment.class);

        when(studentRepository.findById(studentId)).thenReturn(Optional.of(student));
        when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
        when(course.isFull()).thenReturn(false);
        when(enrollmentRepository.save(any(Enrollment.class))).thenReturn(savedEnrollment);
        when(savedEnrollment.getId()).thenReturn(100L);

        Long enrollmentId = enrollmentService.enrollAfterStudentValidation(studentId, courseId);

        assertEquals(100L, enrollmentId);
        verify(course).isFull();
        verify(enrollmentRepository).save(any(Enrollment.class));
        verify(course).increaseEnrolledCount();
        verify(enrollmentRepository, never()).existsByStudentIdAndCourseId(studentId, courseId);
        verify(enrollmentRepository, never()).sumCreditsByStudentId(studentId);
        verify(courseRepository, never()).findSchedulesByCourseId(courseId);
    }
}
