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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class EnrollmentService {

    private static final Logger log = LoggerFactory.getLogger(EnrollmentService.class);
    private static final long TIMING_LOG_INTERVAL = 10L;
    private static final AtomicLong requestCount = new AtomicLong();

    private final StudentRepository studentRepository;
    private final CourseRepository courseRepository;
    private final EnrollmentRepository enrollmentRepository;

    public EnrollmentService(
            StudentRepository studentRepository,
            CourseRepository courseRepository,
            EnrollmentRepository enrollmentRepository
    ) {
        this.studentRepository = studentRepository;
        this.courseRepository = courseRepository;
        this.enrollmentRepository = enrollmentRepository;
    }

    /*
     * 락 범위 비교 전의 전체 수강신청 로직입니다.
     * 단위 테스트와 synchronized 비교 테스트에서 계속 사용합니다.
     */
    @Transactional
    public Long enroll(Long studentId, Long courseId) {
        long methodStartedAt = System.nanoTime();
        long studentFindMillis = -1L;
        long courseFindMillis = -1L;
        long studentStatusMillis = -1L;
        long validationQueryMillis = -1L;
        long capacityCheckMillis = -1L;
        long saveMillis = -1L;
        String outcome = "SUCCESS";

        try {
            long phaseStartedAt = System.nanoTime();
            Student student = findStudent(studentId);
            studentFindMillis = elapsedMillis(phaseStartedAt);

            phaseStartedAt = System.nanoTime();
            Course course = findCourse(courseId);
            courseFindMillis = elapsedMillis(phaseStartedAt);

            phaseStartedAt = System.nanoTime();
            validateStudent(student);
            studentStatusMillis = elapsedMillis(phaseStartedAt);

            phaseStartedAt = System.nanoTime();
            EnrollmentValidationSummary validation =
                    enrollmentRepository.findValidationSummary(studentId, courseId);
            validationQueryMillis = elapsedMillis(phaseStartedAt);

            validateDuplicate(validation);
            validateCreditLimit(student, course, validation);
            validateSchedule(validation);

            phaseStartedAt = System.nanoTime();
            validateCapacity(course);
            capacityCheckMillis = elapsedMillis(phaseStartedAt);

            phaseStartedAt = System.nanoTime();
            Long enrollmentId = saveEnrollment(student, course);
            saveMillis = elapsedMillis(phaseStartedAt);

            return enrollmentId;

        } catch (EnrollmentException exception) {
            outcome = exception.getCode();
            throw exception;

        } finally {
            logTiming(
                    outcome,
                    studentId,
                    courseId,
                    studentFindMillis,
                    courseFindMillis,
                    studentStatusMillis,
                    validationQueryMillis,
                    capacityCheckMillis,
                    saveMillis,
                    methodStartedAt
            );
        }
    }

    /*
     * 학생 락 안에서 실행하지만 과목 락을 얻기 전에 끝내는 읽기 전용 검증입니다.
     *
     * 동일 학생의 요청은 이미 학생 분산 락으로 직렬화되어 있으므로,
     * 중복 과목, 최대 학점, 시간표 검사를 과목 락 밖으로 이동해도 경쟁이 발생하지 않습니다.
     */
    @Transactional(readOnly = true)
    public void validateForEnrollment(Long studentId, Long courseId) {
        Student student = findStudent(studentId);
        Course course = findCourse(courseId);

        validateStudent(student);

        EnrollmentValidationSummary validation =
                enrollmentRepository.findValidationSummary(studentId, courseId);

        validateDuplicate(validation);
        validateCreditLimit(student, course, validation);
        validateSchedule(validation);
    }

    /*
     * 과목 락 안에서 실행되는 최소 쓰기 구간입니다.
     *
     * 과목 조회, 정원 확인, 신청 저장, 신청 인원 증가만 수행해
     * 인기 과목의 과목 락 점유 시간을 줄입니다.
     */
    @Transactional
    public Long completeEnrollment(Long studentId, Long courseId) {
        Student student = studentRepository.getReferenceById(studentId);
        Course course = findCourse(courseId);

        validateCapacity(course);

        return saveEnrollment(student, course);
    }

    /* Scalar query reads current DB state instead of reusing a previously loaded entity. */
    @Transactional(readOnly = true)
    public void validateCourseCapacity(Long courseId) {
        boolean full = courseRepository.findFullStatusById(courseId)
                .orElseThrow(() -> error("COURSE_NOT_FOUND",
                        "과목 정보를 찾을 수 없습니다.", HttpStatus.NOT_FOUND));
        if (full) {
            throw error("COURSE_FULL", "수강 정원이 마감되었습니다.", HttpStatus.CONFLICT);
        }
    }

    private void logTiming(
            String outcome,
            Long studentId,
            Long courseId,
            long studentFindMillis,
            long courseFindMillis,
            long studentStatusMillis,
            long validationQueryMillis,
            long capacityCheckMillis,
            long saveMillis,
            long methodStartedAt
    ) {
        long currentRequestCount = requestCount.incrementAndGet();

        if (currentRequestCount % TIMING_LOG_INTERVAL != 0) {
            return;
        }

        log.info(
                "ENROLLMENT_SERVICE_TIMING requestCount={} outcome={} studentId={} courseId={} "
                        + "studentFindMs={} courseFindMs={} studentStatusMs={} validationQueryMs={} "
                        + "capacityCheckMs={} saveMs={} methodBodyMs={}",
                currentRequestCount,
                outcome,
                studentId,
                courseId,
                studentFindMillis,
                courseFindMillis,
                studentStatusMillis,
                validationQueryMillis,
                capacityCheckMillis,
                saveMillis,
                elapsedMillis(methodStartedAt)
        );
    }

    private long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
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
        Enrollment enrollment =
                enrollmentRepository.save(Enrollment.create(student, course));
        course.increaseEnrolledCount();
        return enrollment.getId();
    }

    private void validateStudent(Student student) {
        if (student.getStatus() != StudentStatus.ACTIVE) {
            throw error(
                    "STUDENT_NOT_ACTIVE",
                    "재학 상태인 학생만 수강신청할 수 있습니다.",
                    HttpStatus.CONFLICT
            );
        }
    }

    private void validateDuplicate(EnrollmentValidationSummary validation) {
        if (validation.getDuplicateEnrollment() > 0) {
            throw error(
                    "DUPLICATE_ENROLLMENT",
                    "이미 신청한 과목입니다.",
                    HttpStatus.CONFLICT
            );
        }
    }

    private void validateCreditLimit(
            Student student,
            Course course,
            EnrollmentValidationSummary validation
    ) {
        if (validation.getCurrentCredits() + course.getCredits()
                > student.getMaxCredits()) {
            throw error(
                    "CREDIT_LIMIT_EXCEEDED",
                    "최대 신청 학점을 초과합니다.",
                    HttpStatus.CONFLICT
            );
        }
    }

    private void validateSchedule(EnrollmentValidationSummary validation) {
        if (validation.getScheduleConflict() > 0) {
            throw error(
                    "SCHEDULE_CONFLICT",
                    "기존 신청 과목과 강의 시간이 겹칩니다.",
                    HttpStatus.CONFLICT
            );
        }
    }

    private void validateCapacity(Course course) {
        if (course.isFull()) {
            throw error(
                    "COURSE_FULL",
                    "수강 정원이 마감되었습니다.",
                    HttpStatus.CONFLICT
            );
        }
    }

    private EnrollmentException error(
            String code,
            String message,
            HttpStatus status
    ) {
        return new EnrollmentException(code, message, status);
    }
}

