package com.jiyun.classenrollment.enrollment.application;

import com.jiyun.classenrollment.common.error.EnrollmentException;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/*
 * 최종 수강신청 진입점입니다.
 *
 * 학생 단위 동시 요청은 Redisson 분산 락으로 직렬화하고,
 * 과목 정원은 MySQL 조건부 UPDATE로 원자적으로 차감합니다.
 *
 * Redis에는 좌석 수를 저장하지 않으므로
 * 좌석의 기준 데이터는 MySQL 한 곳에만 존재합니다.
 */
@Component
public class EnrollmentDatabaseAtomicFacade {

    private static final long STUDENT_LOCK_WAIT_SECONDS = 10L;

    private final EnrollmentService enrollmentService;
    private final RedissonClient redissonClient;

    public EnrollmentDatabaseAtomicFacade(
            EnrollmentService enrollmentService,
            RedissonClient redissonClient
    ) {
        this.enrollmentService = enrollmentService;
        this.redissonClient = redissonClient;
    }

    public Long enroll(Long studentId, Long courseId) {
        RLock studentLock =
                redissonClient.getLock("lock:student:" + studentId);
        boolean studentLockAcquired = false;

        try {
            studentLockAcquired = studentLock.tryLock(
                    STUDENT_LOCK_WAIT_SECONDS,
                    TimeUnit.SECONDS
            );

            if (!studentLockAcquired) {
                throw new EnrollmentException(
                        "LOCK_ACQUISITION_TIMEOUT",
                        "학생의 다른 수강신청 요청이 처리 중입니다. 잠시 후 다시 시도해 주세요.",
                        HttpStatus.SERVICE_UNAVAILABLE
                );
            }

            return enrollmentService.enrollWithAtomicCapacity(
                    studentId,
                    courseId
            );

        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "학생 락 대기 중 요청이 중단되었습니다.",
                    exception
            );

        } finally {
            if (studentLockAcquired
                    && studentLock.isHeldByCurrentThread()) {
                studentLock.unlock();
            }
        }
    }
}
