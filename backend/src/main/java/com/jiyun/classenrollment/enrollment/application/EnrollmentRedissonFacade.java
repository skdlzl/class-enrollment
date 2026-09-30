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
import java.util.concurrent.atomic.AtomicLong;

@Component
public class EnrollmentRedissonFacade {

    private static final Logger log = LoggerFactory.getLogger(EnrollmentRedissonFacade.class);
    private static final long LOCK_WAIT_SECONDS = 10L;
    private static final long TIMING_LOG_INTERVAL = 100L;
    private static final AtomicLong timingLogCount = new AtomicLong();

    private final EnrollmentService enrollmentService;
    private final RedissonClient redissonClient;

    public EnrollmentRedissonFacade(
            EnrollmentService enrollmentService,
            RedissonClient redissonClient
    ) {
        this.enrollmentService = enrollmentService;
        this.redissonClient = redissonClient;
    }

    public Long enroll(Long studentId, Long courseId) {
        long facadeStartedAt = System.nanoTime();

        RLock studentLock = null;
        boolean studentLockAcquired = false;
        long studentLockWaitMillis = 0L;

        try {
            /*
             * 동일 학생의 중복 과목, 최대 학점, 시간표 검증은
             * 여러 서버에서 동시에 통과하면 안 되므로 학생 락을 유지합니다.
             *
             * 과목 정원은 EnrollmentService의 조건부 UPDATE가 원자적으로 처리하므로
             * 인기 과목의 모든 요청을 직렬화하던 과목 분산 락은 제거합니다.
             */
            studentLock = redissonClient.getLock("lock:student:" + studentId);

            long studentLockStartedAt = System.nanoTime();
            studentLockAcquired = studentLock.tryLock(
                    LOCK_WAIT_SECONDS,
                    TimeUnit.SECONDS
            );
            studentLockWaitMillis = elapsedMillis(studentLockStartedAt);

            if (!studentLockAcquired) {
                logTiming(
                        "STUDENT_LOCK_TIMEOUT",
                        studentId,
                        courseId,
                        studentLockWaitMillis,
                        0L,
                        facadeStartedAt
                );
                throw lockTimeout(
                        "학생의 다른 수강신청 요청이 처리 중입니다. 잠시 후 다시 시도해 주세요."
                );
            }

            long serviceStartedAt = System.nanoTime();

            try {
                Long enrollmentId = enrollmentService.enroll(studentId, courseId);

                logTiming(
                        "SUCCESS",
                        studentId,
                        courseId,
                        studentLockWaitMillis,
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
                        elapsedMillis(serviceStartedAt),
                        facadeStartedAt
                );
                throw exception;
            }

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
                    0L,
                    facadeStartedAt
            );
            throw redisUnavailable(exception);

        } finally {
            unlockIfHeld(studentLock, studentLockAcquired);
        }
    }

    private void logTiming(
            String outcome,
            Long studentId,
            Long courseId,
            long studentLockWaitMillis,
            long serviceMillis,
            long facadeStartedAt
    ) {
        long currentCount = timingLogCount.incrementAndGet();

        /*
         * 부하 테스트 중 모든 요청을 콘솔에 출력하면
         * Windows 콘솔 I/O가 응답시간 측정을 왜곡할 수 있습니다.
         * 운영 흐름은 유지하면서 100건마다 한 번만 표본 로그를 남깁니다.
         */
        if (currentCount % TIMING_LOG_INTERVAL != 0) {
            return;
        }

        log.info(
                "ENROLLMENT_TIMING requestCount={} outcome={} studentId={} courseId={} "
                        + "studentLockWaitMs={} serviceMs={} facadeTotalMs={}",
                currentCount,
                outcome,
                studentId,
                courseId,
                studentLockWaitMillis,
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
