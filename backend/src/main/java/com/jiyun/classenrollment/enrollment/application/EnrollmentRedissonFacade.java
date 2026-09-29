package com.jiyun.classenrollment.enrollment.application;

import com.jiyun.classenrollment.common.error.EnrollmentException;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class EnrollmentRedissonFacade {

    private static final Logger log = LoggerFactory.getLogger(EnrollmentRedissonFacade.class);
    private static final long LOCK_WAIT_SECONDS = 10L;

    private final EnrollmentService enrollmentService;
    private final RedissonClient redissonClient;

    public EnrollmentRedissonFacade(EnrollmentService enrollmentService, RedissonClient redissonClient) {
        this.enrollmentService = enrollmentService;
        this.redissonClient = redissonClient;
    }

    public Long enroll(Long studentId, Long courseId) {
        long facadeStartedAt = System.nanoTime();

        RLock studentLock = null;
        RLock courseLock = null;

        boolean studentLockAcquired = false;
        boolean courseLockAcquired = false;
        long studentLockWaitMillis = 0L;
        long courseLockWaitMillis = 0L;

        try {
            studentLock = redissonClient.getLock("lock:student:" + studentId);
            courseLock = redissonClient.getLock("lock:course:" + courseId);

            long studentLockStartedAt = System.nanoTime();
            studentLockAcquired = studentLock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
            studentLockWaitMillis = elapsedMillis(studentLockStartedAt);

            if (!studentLockAcquired) {
                logTiming(
                        "STUDENT_LOCK_TIMEOUT",
                        studentId,
                        courseId,
                        studentLockWaitMillis,
                        0L,
                        0L,
                        facadeStartedAt
                );
                throw lockTimeout("학생의 다른 수강신청 요청이 처리 중입니다. 잠시 후 다시 시도해 주세요.");
            }

            long courseLockStartedAt = System.nanoTime();
            courseLockAcquired = courseLock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
            courseLockWaitMillis = elapsedMillis(courseLockStartedAt);

            if (!courseLockAcquired) {
                logTiming(
                        "COURSE_LOCK_TIMEOUT",
                        studentId,
                        courseId,
                        studentLockWaitMillis,
                        courseLockWaitMillis,
                        0L,
                        facadeStartedAt
                );
                throw lockTimeout("요청이 몰려 수강신청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.");
            }

            long serviceStartedAt = System.nanoTime();

            try {
                Long enrollmentId = enrollmentService.enroll(studentId, courseId);

                logTiming(
                        "SUCCESS",
                        studentId,
                        courseId,
                        studentLockWaitMillis,
                        courseLockWaitMillis,
                        elapsedMillis(serviceStartedAt),
                        facadeStartedAt
                );

                return enrollmentId;

            } catch (EnrollmentException exception) {
                logTiming(
                        "BUSINESS_REJECTED_" + exception.getCode(),
                        studentId,
                        courseId,
                        studentLockWaitMillis,
                        courseLockWaitMillis,
                        elapsedMillis(serviceStartedAt),
                        facadeStartedAt
                );
                throw exception;
            }

        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("수강신청 락 대기 중 요청이 중단되었습니다.", exception);

        } catch (RedisException exception) {
            logTiming(
                    "REDIS_UNAVAILABLE",
                    studentId,
                    courseId,
                    studentLockWaitMillis,
                    courseLockWaitMillis,
                    0L,
                    facadeStartedAt
            );
            throw redisUnavailable(exception);

        } finally {
            unlockIfHeld(courseLock, courseLockAcquired);
            unlockIfHeld(studentLock, studentLockAcquired);
        }
    }

    private void logTiming(
            String outcome,
            Long studentId,
            Long courseId,
            long studentLockWaitMillis,
            long courseLockWaitMillis,
            long serviceMillis,
            long facadeStartedAt
    ) {
        log.info(
                "ENROLLMENT_TIMING outcome={} studentId={} courseId={} "
                        + "studentLockWaitMs={} courseLockWaitMs={} serviceMs={} facadeTotalMs={}",
                outcome,
                studentId,
                courseId,
                studentLockWaitMillis,
                courseLockWaitMillis,
                serviceMillis,
                elapsedMillis(facadeStartedAt)
        );
    }

    private long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
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
            log.warn("Redis 연결 문제로 수강신청 락을 즉시 해제하지 못했습니다.", exception);
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
