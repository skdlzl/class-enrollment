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

    @Transactional
    public Long enroll(Long studentId, Long courseId) {
        long methodStartedAt = System.nanoTime();
        long studentFindMillis = -1L;
        long courseFindMillis = -1L;
        long studentStatusMillis = -1L;
        long validationQueryMillis = -1L;
        long seatReservationMillis = -1L;
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

            /*
             * 정원 확인과 신청 인원 증가를 DB의 조건부 UPDATE 한 번으로 처리합니다.
             *
             * UPDATE 결과가 1이면 이 트랜잭션이 좌석을 확보한 것이고,
             * 0이면 다른 요청이 먼저 마지막 좌석을 확보해 정원이 마감된 것입니다.
             *
             * 아래 신청 저장이 실패하면 @Transactional에 의해 이 UPDATE도 함께 롤백됩니다.
             */
            phaseStartedAt = System.nanoTime();
            reserveSeat(courseId);
            seatReservationMillis = elapsedMillis(phaseStartedAt);

            phaseStartedAt = System.nanoTime();
            Long enrollmentId = saveEnrollment(student, course);
            saveMillis = elapsedMillis(phaseStartedAt);

            return enrollmentId;

        } catch (EnrollmentException exception) {
            outcome = exception.getCode();
            throw exception;

        } finally {
            long currentRequestCount = requestCount.incrementAndGet();

            if (currentRequestCount % TIMING_LOG_INTERVAL == 0) {
                log.info(
                        "ENROLLMENT_SERVICE_TIMING requestCount={} outcome={} studentId={} courseId={} "
                                + "studentFindMs={} courseFindMs={} studentStatusMs={} validationQueryMs={} "
                                + "seatReservationMs={} saveMs={} methodBodyMs={}",
                        currentRequestCount,
                        outcome,
                        studentId,
                        courseId,
                        studentFindMillis,
                        courseFindMillis,
                        studentStatusMillis,
                        validationQueryMillis,
                        seatReservationMillis,
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

    private void reserveSeat(Long courseId) {
        int updatedRows = courseRepository.increaseEnrolledCountIfAvailable(courseId);

        if (updatedRows == 0) {
            throw error(
                    "COURSE_FULL",
                    "수강 정원이 마감되었습니다.",
                    HttpStatus.CONFLICT
            );
        }
    }

    private Long saveEnrollment(Student student, Course course) {
        Enrollment enrollment = enrollmentRepository.save(Enrollment.create(student, course));
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
        if (validation.getCurrentCredits() + course.getCredits() > student.getMaxCredits()) {
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

    private EnrollmentException error(String code, String message, HttpStatus status) {
        return new EnrollmentException(code, message, status);
    }
}
