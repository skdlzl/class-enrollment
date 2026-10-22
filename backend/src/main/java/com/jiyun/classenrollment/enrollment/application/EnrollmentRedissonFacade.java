package com.jiyun.classenrollment.enrollment.application;

import com.jiyun.classenrollment.common.error.EnrollmentException;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class EnrollmentRedissonFacade {

    private static final Logger log =
            LoggerFactory.getLogger(EnrollmentRedissonFacade.class);
    private static final long LOCK_WAIT_SECONDS = 10L;
    private static final long TIMING_LOG_INTERVAL = 100L;
    private static final AtomicLong timingLogCount = new AtomicLong();

    private final EnrollmentService enrollmentService;
    private final RedissonClient redissonClient;
    private final CourseLockStrategy courseLockStrategy;

    public EnrollmentRedissonFacade(
            EnrollmentService enrollmentService,
            RedissonClient redissonClient
    ) {
        this(enrollmentService, redissonClient, CourseLockStrategy.REDIS);
    }

    @Autowired
    public EnrollmentRedissonFacade(
            EnrollmentService enrollmentService,
            RedissonClient redissonClient,
            @Value("${enrollment.course-lock-strategy:PESSIMISTIC}") CourseLockStrategy courseLockStrategy
    ) {
        this.enrollmentService = enrollmentService;
        this.redissonClient = redissonClient;
        this.courseLockStrategy = courseLockStrategy;
    }

    @Value("${enrollment.local-gate-enabled:false}")
    private boolean localGateEnabled;
    @Value("${enrollment.local-gate-wait-ms:10000}")
    private long localGateWaitMs = 10000;
    private final LocalCourseGate localGate = new LocalCourseGate();
    @Autowired
    private EnrollmentDbAdmission dbAdmission = new EnrollmentDbAdmission(0, 3000, 500);

    @Autowired
    private com.jiyun.classenrollment.experiment.EnrollmentTimings timings =
            new com.jiyun.classenrollment.experiment.EnrollmentTimings(false);

    private final AtomicLong gateSamples = new AtomicLong();

    public Long enroll(Long studentId, Long courseId) {
        if (!localGateEnabled) return enrollAfterGate(studentId, courseId);
        long started = System.nanoTime();
        try (var permit = localGate.acquire(courseId, localGateWaitMs)) {
            long waited = elapsedMillis(started);
            if (permit == null) {
                log.warn("LOCAL_GATE_TIMING strategy={} courseId={} outcome=LOCAL_GATE_TIMEOUT gateWaitMs={}",
                        courseLockStrategy, courseId, waited);
                throw new EnrollmentException("LOCAL_GATE_TIMEOUT", "과목 요청 대기 시간이 초과됐습니다.",
                        HttpStatus.SERVICE_UNAVAILABLE);
            }
            if (gateSamples.incrementAndGet() % TIMING_LOG_INTERVAL == 0) {
                log.info("LOCAL_GATE_TIMING strategy={} courseId={} outcome=ACQUIRED gateWaitMs={}",
                        courseLockStrategy, courseId, waited);
            }
            // Validate in a fresh transaction AFTER local admission. The final
            // write still holds the Redis/DB lock and rechecks current capacity.
            // Gate permit is held, but no Redis lock or write transaction yet.
            // A stale "not full" read is safe: final locked capacity validation remains.
            enrollmentService.checkCapacityBeforeStudentLock(courseId);
            return enrollAfterGate(studentId, courseId);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new EnrollmentException("LOCAL_GATE_INTERRUPTED", "과목 요청 대기가 중단됐습니다.",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private Long enrollAfterGate(Long studentId, Long courseId) {
        long facadeStartedAt = System.nanoTime();

        RLock studentLock = null;
        RLock courseLock = null;

        boolean studentLockAcquired = false;
        boolean courseLockAcquired = false;

        long studentLockWaitMillis = 0L;
        long validationMillis = 0L;
        long courseLockWaitMillis = 0L;
        long writeMillis = 0L;

        try {
            if (courseLockStrategy == CourseLockStrategy.DB_ONLY) {
                long started = System.nanoTime();
                try {
                    Long id = dbAdmission.execute(() -> timings.proxy(() -> enrollmentService.enrollWithDatabaseLocks(studentId, courseId)));
                    writeMillis = elapsedMillis(started);
                    logTiming("SUCCESS", studentId, courseId, 0, 0, 0, writeMillis, facadeStartedAt);
                    return id;
                } finally {
                    writeMillis = elapsedMillis(started);
                }
            }
            studentLock = redissonClient.getLock(
                    "lock:student:" + studentId
            );
            if (courseLockStrategy == CourseLockStrategy.REDIS) {
                courseLock = redissonClient.getLock("lock:course:" + courseId);
            }

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
                        writeMillis,
                        facadeStartedAt
                );
                throw lockTimeout(
                        "학생의 다른 수강신청 요청이 처리 중입니다. 잠시 후 다시 시도해 주세요."
                );
            }

            // Only REDIS waits for an external course lock before the guarded write.
            // Mixed mode validates once inside the student DB lock, before taking
            // the course DB lock. A separate read-only validation would duplicate
            // three queries and a connection acquisition without adding safety.
            if (courseLockStrategy == CourseLockStrategy.REDIS) {
                phaseStartedAt = System.nanoTime();
                try {
                    enrollmentService.validateForEnrollment(studentId, courseId);
                } finally {
                    validationMillis = elapsedMillis(phaseStartedAt);
                }
                phaseStartedAt = System.nanoTime();
                courseLockAcquired = courseLock.tryLock(
                        LOCK_WAIT_SECONDS,
                        TimeUnit.SECONDS
                );
                courseLockWaitMillis = elapsedMillis(phaseStartedAt);

                if (!courseLockAcquired) {
                    logTiming(
                            "COURSE_LOCK_TIMEOUT",
                            studentId,
                            courseId,
                            studentLockWaitMillis,
                            validationMillis,
                            courseLockWaitMillis,
                            writeMillis,
                            facadeStartedAt
                    );
                    throw lockTimeout(
                            "요청이 몰려 수강신청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."
                    );
                }

            }

            /*
             * 쓰기 트랜잭션은 학생 DB 락과 재검증 후 과목 DB 락을 획득합니다.
             * DB 커밋이 완료된 뒤 completeEnrollment()가 반환되므로
             * 그 다음에 과목 락을 해제합니다.
             */
            phaseStartedAt = System.nanoTime();
            // DB mode writeMillis includes pool/transaction acquisition, DB lock
            // wait, writes and commit. courseLockWaitMillis measures Redis ONLY.
            Long enrollmentId;
            try {
                enrollmentId = dbAdmission.execute(() -> timings.proxy(() -> courseLockStrategy == CourseLockStrategy.PESSIMISTIC
                        ? enrollmentService.completeEnrollmentWithPessimisticLock(studentId, courseId)
                        : enrollmentService.completeEnrollment(studentId, courseId)));
            } finally {
                writeMillis = elapsedMillis(phaseStartedAt);
            }

            logTiming(
                    "SUCCESS",
                    studentId,
                    courseId,
                    studentLockWaitMillis,
                    validationMillis,
                    courseLockWaitMillis,
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
                    writeMillis,
                    facadeStartedAt
            );
            throw exception;

        } catch (PessimisticLockingFailureException | jakarta.persistence.LockTimeoutException
                 | jakarta.persistence.PessimisticLockException exception) {
            logTiming("DB_LOCK_FAILURE", studentId, courseId, studentLockWaitMillis,
                    validationMillis, courseLockWaitMillis, writeMillis, facadeStartedAt);
            // Includes translated DB lock timeout/deadlock; no implicit retry.
            EnrollmentException failure = new EnrollmentException(
                    "DB_LOCK_FAILURE", "DB 잠금 문제로 수강신청을 처리하지 못했습니다.",
                    HttpStatus.SERVICE_UNAVAILABLE);
            failure.initCause(exception);
            throw failure;

        } catch (org.springframework.transaction.TransactionException
                 | org.springframework.dao.DataAccessResourceFailureException
                 | org.springframework.dao.QueryTimeoutException exception) {
            // A connection failure at COMMIT does not prove rollback. Do not auto-retry.
            log.warn("ENROLLMENT_RESULT_UNKNOWN studentId={} courseId={}", studentId, courseId, exception);
            EnrollmentException failure = new EnrollmentException("ENROLLMENT_RESULT_UNKNOWN",
                    "처리 결과를 확인하지 못했습니다. 신청 내역을 먼저 확인해 주세요.",
                    HttpStatus.SERVICE_UNAVAILABLE);
            failure.initCause(exception);
            throw failure;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            EnrollmentException failure = new EnrollmentException("LOCK_INTERRUPTED",
                    "수강신청 대기가 중단됐습니다. 신청 내역을 확인해 주세요.",
                    HttpStatus.SERVICE_UNAVAILABLE);
            failure.initCause(exception);
            throw failure;

        } catch (RedisException exception) {
            logTiming(
                    "REDIS_UNAVAILABLE",
                    studentId,
                    courseId,
                    studentLockWaitMillis,
                    validationMillis,
                    courseLockWaitMillis,
                    writeMillis,
                    facadeStartedAt
            );
            throw redisUnavailable(exception);

        } finally {
            try {
                unlockIfHeld(courseLock, courseLockAcquired);
            } finally {
                unlockIfHeld(studentLock, studentLockAcquired);
            }
        }
    }

    private void logTiming(
            String outcome,
            Long studentId,
            Long courseId,
            long studentLockWaitMillis,
            long validationMillis,
            long courseLockWaitMillis,
            long writeMillis,
            long facadeStartedAt
    ) {
        long currentCount = timingLogCount.incrementAndGet();

        // Keep every infrastructure failure; sample normal success/business rejection.
        boolean infrastructureFailure = outcome.endsWith("_TIMEOUT")
                || outcome.equals("REDIS_UNAVAILABLE") || outcome.equals("DB_LOCK_FAILURE");
        if (outcome.equals("BUSINESS_REJECTED_LOCK_ACQUISITION_TIMEOUT")) {
            return; // Already logged with the student/course phase above.
        }
        if (!infrastructureFailure && currentCount % TIMING_LOG_INTERVAL != 0) {
            return;
        }

        log.info(
                "ENROLLMENT_TIMING strategy={} requestCount={} outcome={} studentId={} courseId={} "
                        + "studentLockWaitMs={} validationMs={} courseLockWaitMs={} "
                        + "writeMs={} facadeTotalMs={}",
                courseLockStrategy,
                currentCount,
                outcome,
                studentId,
                courseId,
                studentLockWaitMillis,
                validationMillis,
                courseLockWaitMillis,
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
        } catch (RedisException | IllegalMonitorStateException exception) {
            log.warn(
                    "Redis 연결 문제 또는 락 만료로 해제하지 못했습니다. DB 처리 결과는 유지합니다.",
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
