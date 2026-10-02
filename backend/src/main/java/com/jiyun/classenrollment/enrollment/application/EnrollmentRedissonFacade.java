package com.jiyun.classenrollment.enrollment.application;

import com.jiyun.classenrollment.common.error.EnrollmentException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class EnrollmentRedissonFacade {

    private static final Logger log =
            LoggerFactory.getLogger(EnrollmentRedissonFacade.class);
    private static final long LOCK_WAIT_SECONDS = 10L;
    @Value("${enrollment.timing-log-interval:100}")
    private long timingLogInterval = 100L;
    private static final AtomicLong timingLogCount = new AtomicLong();

    private final EnrollmentService enrollmentService;
    private final RedissonClient redissonClient;
    private final boolean localCourseGateEnabled;
    private final CourseAdmissionGate courseAdmissionGate = new CourseAdmissionGate();

    // Direct construction used by existing baseline tests.
    public EnrollmentRedissonFacade(EnrollmentService enrollmentService,
                                    RedissonClient redissonClient) {
        this(enrollmentService, redissonClient, false);
    }

    @Autowired
    public EnrollmentRedissonFacade(
            EnrollmentService enrollmentService,
            RedissonClient redissonClient,
            @Value("${enrollment.local-course-gate.enabled:false}") boolean enabled
    ) {
        this.enrollmentService = enrollmentService;
        this.redissonClient = redissonClient;
        this.localCourseGateEnabled = enabled;
    }

    public Long enroll(Long studentId, Long courseId) {
        long facadeStartedAt = System.nanoTime();

        RLock studentLock = null;
        RLock courseLock = null;
        CourseAdmissionGate.Ticket courseTicket = null;

        boolean studentLockAcquired = false;
        boolean courseLockAcquired = false;

        long studentLockWaitMillis = 0L;
        long validationMillis = 0L;
        long courseLockWaitMillis = 0L;
        long localCourseWaitMillis = 0L;
        long capacityReadMillis = 0L;
        long writeMillis = 0L;

        try {
            studentLock = redissonClient.getLock(
                    "lock:student:" + studentId
            );
            courseLock = redissonClient.getLock(
                    "lock:course:" + courseId
            );

            long phaseStartedAt = System.nanoTime();
            studentLockAcquired = studentLock.tryLock(
                    LOCK_WAIT_SECONDS,
                    TimeUnit.SECONDS
            );
            studentLockWaitMillis = elapsedMillis(phaseStartedAt);

            if (!studentLockAcquired) {
                logTiming(
                        "STUDENT_LOCK_TIMEOUT",
                        studentId,
                        courseId,
                        studentLockWaitMillis,
                        validationMillis,
                        courseLockWaitMillis,
                        localCourseWaitMillis,
                        capacityReadMillis,
                        writeMillis,
                        facadeStartedAt
                );
                throw lockTimeout(
                        "학생의 다른 수강신청 요청이 처리 중입니다. 잠시 후 다시 시도해 주세요."
                );
            }

            /*
             * 학생 관련 검증은 학생 락으로 이미 보호됩니다.
             * 과목 락을 기다리기 전에 읽기 전용 검증을 끝내 과목 락 점유 시간을 줄입니다.
             */
            phaseStartedAt = System.nanoTime();
            enrollmentService.validateForEnrollment(studentId, courseId);
            validationMillis = elapsedMillis(phaseStartedAt);

            phaseStartedAt = System.nanoTime();
            if (localCourseGateEnabled) {
                // Only one request per course in this JVM may contend for Redis.
                // The distributed lock still protects against the other JVM.
                courseTicket = courseAdmissionGate.register(courseId);
                long localStartedAt = System.nanoTime();
                try {
                    if (!courseTicket.tryAcquire(remainingCourseBudget(phaseStartedAt),
                            TimeUnit.NANOSECONDS)) {
                        throw lockTimeout("요청이 몰려 수강신청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.");
                    }
                } finally {
                    localCourseWaitMillis = elapsedMillis(localStartedAt);
                }
                // Once per admitted request, never polling while queued.
                long readStartedAt = System.nanoTime();
                try {
                    enrollmentService.validateCourseCapacity(courseId);
                } finally {
                    capacityReadMillis = elapsedMillis(readStartedAt);
                }
                long remaining = remainingCourseBudget(phaseStartedAt);
                if (remaining > 0L) {
                    long redisStartedAt = System.nanoTime();
                    try {
                        courseLockAcquired = courseLock.tryLock(remaining, TimeUnit.NANOSECONDS);
                    } finally {
                        courseLockWaitMillis = elapsedMillis(redisStartedAt);
                    }
                }
            } else {
                courseLockAcquired = courseLock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
                courseLockWaitMillis = elapsedMillis(phaseStartedAt);
            }

            if (!courseLockAcquired) {
                logTiming(
                        "COURSE_LOCK_TIMEOUT",
                        studentId,
                        courseId,
                        studentLockWaitMillis,
                        validationMillis,
                        courseLockWaitMillis,
                        localCourseWaitMillis,
                        capacityReadMillis,
                        writeMillis,
                        facadeStartedAt
                );
                throw lockTimeout(
                        "요청이 몰려 수강신청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."
                );
            }

            /*
             * 과목 락 안에서는 정원 확인과 저장만 수행합니다.
             * DB 커밋이 완료된 뒤 completeEnrollment()가 반환되므로
             * 그 다음에 과목 락을 해제합니다.
             */
            phaseStartedAt = System.nanoTime();
            Long enrollmentId =
                    enrollmentService.completeEnrollment(studentId, courseId);
            writeMillis = elapsedMillis(phaseStartedAt);

            logTiming(
                    "SUCCESS",
                    studentId,
                    courseId,
                    studentLockWaitMillis,
                    validationMillis,
                    courseLockWaitMillis,
                    localCourseWaitMillis,
                    capacityReadMillis,
                    writeMillis,
                    facadeStartedAt
            );

            return enrollmentId;

        } catch (EnrollmentException exception) {
            logTiming(
                    "BUSINESS_REJECTED_" + exception.getCode(),
                    studentId,
                    courseId,
                    studentLockWaitMillis,
                    validationMillis,
                    courseLockWaitMillis,
                    localCourseWaitMillis,
                    capacityReadMillis,
                    writeMillis,
                    facadeStartedAt
            );
            throw exception;

        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "수강신청 락 대기 중 요청이 중단되었습니다.",
                    exception
            );

        } catch (RedisException exception) {
            logTiming(
                    "REDIS_UNAVAILABLE",
                    studentId,
                    courseId,
                    studentLockWaitMillis,
                    validationMillis,
                    courseLockWaitMillis,
                    localCourseWaitMillis,
                    capacityReadMillis,
                    writeMillis,
                    facadeStartedAt
            );
            throw redisUnavailable(exception);

        } finally {
            try {
                unlockIfHeld(courseLock, courseLockAcquired);
            } finally {
                try {
                    if (courseTicket != null) {
                        courseTicket.close();
                    }
                } finally {
                    unlockIfHeld(studentLock, studentLockAcquired);
                }
            }
        }
    }

    private long remainingCourseBudget(long startedAt) {
        // DB/Redis commands themselves are not forcibly interrupted by this budget.
        return Math.max(0L, TimeUnit.SECONDS.toNanos(LOCK_WAIT_SECONDS)
                - (System.nanoTime() - startedAt));
    }

    private void logTiming(
            String outcome,
            Long studentId,
            Long courseId,
            long studentLockWaitMillis,
            long validationMillis,
            long courseLockWaitMillis,
            long localCourseWaitMillis,
            long capacityReadMillis,
            long writeMillis,
            long facadeStartedAt
    ) {
        long currentCount = timingLogCount.incrementAndGet();

        if (currentCount % Math.max(1L, timingLogInterval) != 0) {
            return;
        }

        log.info(
                "ENROLLMENT_TIMING requestCount={} outcome={} studentId={} courseId={} "
                        + "studentLockWaitMs={} validationMs={} courseLockWaitMs={} "
                        + "localCourseWaitMs={} capacityReadMs={} writeMs={} facadeTotalMs={}",
                currentCount,
                outcome,
                studentId,
                courseId,
                studentLockWaitMillis,
                validationMillis,
                courseLockWaitMillis,
                localCourseWaitMillis,
                capacityReadMillis,
                writeMillis,
                elapsedMillis(facadeStartedAt)
        );
    }

    private long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - startedAt
        );
    }

    private void unlockIfHeld(RLock lock, boolean acquired) {
        if (!acquired || lock == null) {
            return;
        }

        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (RedisException exception) {
            log.warn(
                    "Redis 연결 문제로 수강신청 락을 즉시 해제하지 못했습니다.",
                    exception
            );
        }
    }

    private EnrollmentException lockTimeout(String message) {
        return new EnrollmentException(
                "LOCK_ACQUISITION_TIMEOUT",
                message,
                HttpStatus.SERVICE_UNAVAILABLE
        );
    }

    private EnrollmentException redisUnavailable(RedisException cause) {
        EnrollmentException exception = new EnrollmentException(
                "REDIS_UNAVAILABLE",
                "수강신청 동시성 제어 시스템에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.",
                HttpStatus.SERVICE_UNAVAILABLE
        );
        exception.initCause(cause);
        return exception;
    }
}

