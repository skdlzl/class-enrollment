package com.jiyun.classenrollment.enrollment.application;

import com.jiyun.classenrollment.common.error.EnrollmentException;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class EnrollmentRedissonFacade {

    private static final long LOCK_WAIT_SECONDS = 5L;

    private final EnrollmentService enrollmentService;
    private final RedissonClient redissonClient;

    public EnrollmentRedissonFacade(EnrollmentService enrollmentService, RedissonClient redissonClient) {
        this.enrollmentService = enrollmentService;
        this.redissonClient = redissonClient;
    }

    public Long enroll(Long studentId, Long courseId) {
        /*
         * 학생 락은 같은 학생이 서로 다른 과목을 동시에 신청할 때 발생할 수 있는
         * 중복, 최대 학점, 시간표 경합을 막습니다.
         */
        RLock studentLock = redissonClient.getLock("lock:student:" + studentId);

        /*
         * 과목 락은 여러 서버에서 같은 과목의 정원을 동시에 변경하지 못하게 합니다.
         */
        RLock courseLock = redissonClient.getLock("lock:course:" + courseId);

        boolean studentLockAcquired = false;
        boolean courseLockAcquired = false;

        try {
            /*
             * 모든 요청이 학생 락을 먼저 획득하도록 순서를 통일합니다.
             * 락 획득 순서가 요청마다 달라질 때 발생할 수 있는 교착 상태를 예방합니다.
             */
            studentLockAcquired = studentLock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);

            if (!studentLockAcquired) {
                throw lockTimeout("학생의 다른 수강신청 요청이 처리 중입니다. 잠시 후 다시 시도해 주세요.");
            }

            /*
             * 학생 락을 보유한 상태에서 중복 신청, 최대 학점, 시간표를 검증합니다.
             * 이 과정은 과목 락 밖에서 실행되므로 같은 과목을 신청하는 다른 학생과 병렬로 처리됩니다.
             */
            enrollmentService.validateStudentConditions(studentId, courseId);

            /*
             * 학생 검증이 끝난 뒤 과목 락을 획득합니다.
             */
            courseLockAcquired = courseLock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);

            if (!courseLockAcquired) {
                throw lockTimeout("요청이 몰려 수강신청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.");
            }

            /*
             * 과목 락 안에서는 최신 정원 확인, 신청 저장, 인원 증가만 처리합니다.
             */
            return enrollmentService.enrollAfterStudentValidation(studentId, courseId);

        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("수강신청 락 대기 중 요청이 중단되었습니다.", exception);

        } finally {
            /*
             * 획득 순서의 반대인 과목 락, 학생 락 순서로 해제합니다.
             */
            unlockIfHeld(courseLock, courseLockAcquired);
            unlockIfHeld(studentLock, studentLockAcquired);
        }
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
