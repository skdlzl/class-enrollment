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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EnrollmentService {
    private final StudentRepository studentRepository;
    private final CourseRepository courseRepository;
    private final EnrollmentRepository enrollmentRepository;

    public EnrollmentService(StudentRepository studentRepository, CourseRepository courseRepository,
                             EnrollmentRepository enrollmentRepository) {
        this.studentRepository = studentRepository;
        this.courseRepository = courseRepository;
        this.enrollmentRepository = enrollmentRepository;
    }

    /*
     * 분산 락 적용 전 동작과 기존 단위 테스트를 유지하기 위한 전체 수강신청 메서드입니다.
     */
    @Transactional
    public Long enroll(Long studentId, Long courseId) {
        Student student = findStudent(studentId);
        Course course = findCourse(courseId);

        validateStudent(student);
        validateDuplicate(studentId, courseId);
        validateCreditLimit(student, course);
        validateSchedule(studentId, courseId);
        validateCapacity(course);

        return saveEnrollment(student, course);
    }

    /*
     * 학생별 락을 획득한 상태에서 실행합니다.
     *
     * 중복 신청, 최대 학점, 시간표 충돌은 같은 학생의 서로 다른 과목 요청 사이에서도
     * 경합할 수 있으므로 과목 락이 아니라 학생 락으로 보호합니다.
     */
    @Transactional(readOnly = true)
    public void validateStudentConditions(Long studentId, Long courseId) {
        Student student = findStudent(studentId);
        Course course = findCourse(courseId);

        validateStudent(student);
        validateDuplicate(studentId, courseId);
        validateCreditLimit(student, course);
        validateSchedule(studentId, courseId);
    }

    /*
     * 학생 검증이 끝나고 과목별 락까지 획득한 상태에서 실행합니다.
     *
     * 과목 락 안에는 정원 정합성에 직접 영향을 주는 작업만 남깁니다.
     * 이 메서드의 트랜잭션이 커밋된 뒤 Facade로 반환되므로,
     * 커밋이 끝나기 전에 과목 락이 해제되지 않습니다.
     */
    @Transactional
    public Long enrollAfterStudentValidation(Long studentId, Long courseId) {
        Student student = findStudent(studentId);
        Course course = findCourse(courseId);

        validateCapacity(course);

        return saveEnrollment(student, course);
    }

    private Student findStudent(Long studentId) {
        return studentRepository.findById(studentId)
                .orElseThrow(() -> error(
                        "STUDENT_NOT_FOUND",
                        "학생 정보를 찾을 수 없습니다.",
                        HttpStatus.NOT_FOUND
                ));
    }

    private Course findCourse(Long courseId) {
        return courseRepository.findById(courseId)
                .orElseThrow(() -> error(
                        "COURSE_NOT_FOUND",
                        "과목 정보를 찾을 수 없습니다.",
                        HttpStatus.NOT_FOUND
                ));
    }

    private Long saveEnrollment(Student student, Course course) {
        Enrollment enrollment = enrollmentRepository.save(Enrollment.create(student, course));
        course.increaseEnrolledCount();
        return enrollment.getId();
    }

    private void validateStudent(Student student) {
        if (student.getStatus() != StudentStatus.ACTIVE) {
            throw error("STUDENT_NOT_ACTIVE", "재학 상태인 학생만 수강신청할 수 있습니다.", HttpStatus.CONFLICT);
        }
    }

    private void validateDuplicate(Long studentId, Long courseId) {
        if (enrollmentRepository.existsByStudentIdAndCourseId(studentId, courseId)) {
            throw error("DUPLICATE_ENROLLMENT", "이미 신청한 과목입니다.", HttpStatus.CONFLICT);
        }
    }

    private void validateCreditLimit(Student student, Course course) {
        int currentCredits = enrollmentRepository.sumCreditsByStudentId(student.getId());
        if (currentCredits + course.getCredits() > student.getMaxCredits()) {
            throw error("CREDIT_LIMIT_EXCEEDED", "최대 신청 학점을 초과합니다.", HttpStatus.CONFLICT);
        }
    }

    private void validateSchedule(Long studentId, Long courseId) {
        for (CourseSchedule schedule : courseRepository.findSchedulesByCourseId(courseId)) {
            long conflicts = enrollmentRepository.countScheduleConflicts(
                    studentId,
                    schedule.getDayOfWeek(),
                    schedule.getStartTime(),
                    schedule.getEndTime()
            );
            if (conflicts > 0) {
                throw error("SCHEDULE_CONFLICT", "기존 신청 과목과 강의 시간이 겹칩니다.", HttpStatus.CONFLICT);
            }
        }
    }

    private void validateCapacity(Course course) {
        if (course.isFull()) {
            throw error("COURSE_FULL", "수강 정원이 마감되었습니다.", HttpStatus.CONFLICT);
        }
    }

    private EnrollmentException error(String code, String message, HttpStatus status) {
        return new EnrollmentException(code, message, status);
    }
}
