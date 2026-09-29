package com.jiyun.classenrollment.enrollment.application;

import com.jiyun.classenrollment.common.error.EnrollmentException;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class EnrollmentRedissonFacade {

    private static final Logger log = LoggerFactory.getLogger(EnrollmentRedissonFacade.class);
    private static final long LOCK_WAIT_SECONDS = 10L;
    private static final long TIMEOUT_LOG_INTERVAL = 100L;
    private static final AtomicLong timeoutCount = new AtomicLong();

    private final EnrollmentService enrollmentService;
    private final RedissonClient redissonClient;

    public EnrollmentRedissonFacade(EnrollmentService enrollmentService, RedissonClient redissonClient) {
        this.enrollmentService = enrollmentService;
        this.redissonClient = redissonClient;
    }

    public Long enroll(Long studentId, Long courseId) {
        long facadeStartedAt = System.nanoTime();

        RLock studentLock = redissonClient.getLock("lock:student:" + studentId);
        RLock courseLock = redissonClient.getLock("lock:course:" + courseId);

        boolean studentLockAcquired = false;
        boolean courseLockAcquired = false;
        long studentLockWaitMillis = 0L;
        long courseLockWaitMillis = 0L;

        try {
            long studentLockStartedAt = System.nanoTime();
            studentLockAcquired = studentLock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
            studentLockWaitMillis = elapsedMillis(studentLockStartedAt);

            if (!studentLockAcquired) {
                logTimeoutIfNeeded(
                        "STUDENT_LOCK_TIMEOUT",
                        studentId,
                        courseId,
                        studentLockWaitMillis,
                        0L,
                        facadeStartedAt
                );
                throw lockTimeout("학생의 다른 수강신청 요청이 처리 중입니다. 잠시 후 다시 시도해 주세요.");
            }

            long courseLockStartedAt = System.nanoTime();
            courseLockAcquired = courseLock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
            courseLockWaitMillis = elapsedMillis(courseLockStartedAt);

            if (!courseLockAcquired) {
                logTimeoutIfNeeded(
                        "COURSE_LOCK_TIMEOUT",
                        studentId,
                        courseId,
                        studentLockWaitMillis,
                        courseLockWaitMillis,
                        facadeStartedAt
                );
                throw lockTimeout("요청이 몰려 수강신청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.");
            }

            long serviceStartedAt = System.nanoTime();

            try {
                Long enrollmentId = enrollmentService.enroll(studentId, courseId);

                log.info(
                        "ENROLLMENT_TIMING outcome=SUCCESS studentId={} courseId={} "
                                + "studentLockWaitMs={} courseLockWaitMs={} serviceMs={} facadeTotalMs={}",
                        studentId,
                        courseId,
                        studentLockWaitMillis,
                        courseLockWaitMillis,
                        elapsedMillis(serviceStartedAt),
                        elapsedMillis(facadeStartedAt)
                );

                return enrollmentId;

            } catch (EnrollmentException exception) {
                log.info(
                        "ENROLLMENT_TIMING outcome=BUSINESS_REJECTED code={} studentId={} courseId={} "
                                + "studentLockWaitMs={} courseLockWaitMs={} serviceMs={} facadeTotalMs={}",
                        exception.getCode(),
                        studentId,
                        courseId,
                        studentLockWaitMillis,
                        courseLockWaitMillis,
                        elapsedMillis(serviceStartedAt),
                        elapsedMillis(facadeStartedAt)
                );
                throw exception;
            }

        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("수강신청 락 대기 중 요청이 중단되었습니다.", exception);

        } finally {
            unlockIfHeld(courseLock, courseLockAcquired);
            unlockIfHeld(studentLock, studentLockAcquired);
        }
    }

    private void logTimeoutIfNeeded(
            String outcome,
            Long studentId,
            Long courseId,
            long studentLockWaitMillis,
            long courseLockWaitMillis,
            long facadeStartedAt
    ) {
        long currentTimeoutCount = timeoutCount.incrementAndGet();

        if (currentTimeoutCount % TIMEOUT_LOG_INTERVAL != 0) {
            return;
        }

        log.info(
                "ENROLLMENT_TIMING outcome={} timeoutCount={} studentId={} courseId={} "
                        + "studentLockWaitMs={} courseLockWaitMs={} serviceMs=0 facadeTotalMs={}",
                outcome,
                currentTimeoutCount,
                studentId,
                courseId,
                studentLockWaitMillis,
                courseLockWaitMillis,
                elapsedMillis(facadeStartedAt)
        );
    }

    private long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private void unlockIfHeld(RLock lock, boolean acquired) {
        if (acquired && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    private EnrollmentException lockTimeout(String message) {
        return new EnrollmentException(
                "LOCK_ACQUISITION_TIMEOUT",
                message,
                HttpStatus.SERVICE_UNAVAILABLE
        );
    }
}
