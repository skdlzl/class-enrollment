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

    public EnrollmentService(StudentRepository studentRepository, CourseRepository courseRepository,
                             EnrollmentRepository enrollmentRepository) {
        this.studentRepository = studentRepository;
        this.courseRepository = courseRepository;
        this.enrollmentRepository = enrollmentRepository;
    }

    /*
     * 기존 분산락 비교 테스트에서 사용하는 수강신청 로직입니다.
     */
    @Transactional
    public Long enroll(Long studentId, Long courseId) {
        return enrollInternal(studentId, courseId, false);
    }

    /*
     * Redis에서 좌석을 먼저 예약한 요청이 사용하는 로직입니다.
     * DB 신청 인원은 읽고 수정하지 않고 조건부 UPDATE 한 번으로 증가시킵니다.
     */
    @Transactional
    public Long enrollWithReservedSeat(Long studentId, Long courseId) {
        return enrollInternal(studentId, courseId, true);
    }

    private Long enrollInternal(Long studentId, Long courseId, boolean seatReserved) {
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

            if (!seatReserved) {
                phaseStartedAt = System.nanoTime();
                validateCapacity(course);
                capacityCheckMillis = elapsedMillis(phaseStartedAt);
            }

            phaseStartedAt = System.nanoTime();
            Long enrollmentId = seatReserved
                    ? saveReservedEnrollment(student, course)
                    : saveEnrollment(student, course);
            saveMillis = elapsedMillis(phaseStartedAt);

            return enrollmentId;

        } catch (EnrollmentException exception) {
            outcome = exception.getCode();
            throw exception;

        } finally {
            long currentRequestCount = requestCount.incrementAndGet();

            if (currentRequestCount % TIMING_LOG_INTERVAL == 0) {
                log.info(
                        "ENROLLMENT_SERVICE_TIMING requestCount={} mode={} outcome={} studentId={} courseId={} "
                                + "studentFindMs={} courseFindMs={} studentStatusMs={} validationQueryMs={} "
                                + "capacityCheckMs={} saveMs={} methodBodyMs={}",
                        currentRequestCount,
                        seatReserved ? "ATOMIC_RESERVATION" : "DISTRIBUTED_LOCK",
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
        }
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
        Enrollment enrollment = enrollmentRepository.save(Enrollment.create(student, course));
        course.increaseEnrolledCount();
        return enrollment.getId();
    }

    private Long saveReservedEnrollment(Student student, Course course) {
        /*
         * 과목 행을 먼저 UPDATE하여 배타 락 획득 순서를 통일합니다.
         *
         * INSERT를 먼저 하면 외래키 검사로 여러 트랜잭션이 courses 행의 공유 락을
         * 동시에 보유한 뒤 배타 락으로 전환하려 하면서 데드락이 발생할 수 있습니다.
         */
        int updatedRows = courseRepository.incrementEnrolledCountIfAvailable(course.getId());

        if (updatedRows != 1) {
            throw error(
                    "COURSE_FULL",
                    "수강 정원이 마감되었습니다.",
                    HttpStatus.CONFLICT
            );
        }

        Enrollment enrollment =
                enrollmentRepository.save(Enrollment.create(student, course));

        return enrollment.getId();
    }

    private void validateStudent(Student student) {
        if (student.getStatus() != StudentStatus.ACTIVE) {
            throw error("STUDENT_NOT_ACTIVE", "재학 상태인 학생만 수강신청할 수 있습니다.", HttpStatus.CONFLICT);
        }
    }

    private void validateDuplicate(EnrollmentValidationSummary validation) {
        if (validation.getDuplicateEnrollment() > 0) {
            throw error("DUPLICATE_ENROLLMENT", "이미 신청한 과목입니다.", HttpStatus.CONFLICT);
        }
    }

    private void validateCreditLimit(
            Student student,
            Course course,
            EnrollmentValidationSummary validation
    ) {
        if (validation.getCurrentCredits() + course.getCredits() > student.getMaxCredits()) {
            throw error("CREDIT_LIMIT_EXCEEDED", "최대 신청 학점을 초과합니다.", HttpStatus.CONFLICT);
        }
    }

    private void validateSchedule(EnrollmentValidationSummary validation) {
        if (validation.getScheduleConflict() > 0) {
            throw error("SCHEDULE_CONFLICT", "기존 신청 과목과 강의 시간이 겹칩니다.", HttpStatus.CONFLICT);
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
