package com.jiyun.classenrollment.enrollment.application;

import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import org.redisson.api.RLock;

import java.util.concurrent.TimeUnit;

@Component
public class EnrollmentRedissonFacade {

    /*
     * 실제 수강신청과 DB 트랜잭션을 처리하는 서비스
     */
    private final EnrollmentService enrollmentService;

    /*
     * Redis에 저장되는 분산 락을 사용하기 위한 Redisson 객체
     */
    private final RedissonClient redissonClient;

    /*
     * Spring이 두 객체를 주입합니다.
     */
    public EnrollmentRedissonFacade(EnrollmentService enrollmentService, RedissonClient redissonClient) {
        this.enrollmentService = enrollmentService;
        this.redissonClient = redissonClient;
    }

    public Long enroll(Long studentId, Long courseId)
            throws InterruptedException {

        /*
         * 과목별 Redis 락을 가져옵니다.
         *
         * 예:
         * courseId = 2
         * Redis 락 이름 = lock:course:2
         *
         * 여러 서버에서 호출해도 같은 courseId이면
         * 동일한 Redis 락을 사용합니다.
         */
        RLock lock = redissonClient.getLock("lock:course:" + courseId);

        /*
         * Redis 락 획득 성공 여부를 저장합니다.
         */
        boolean acquired = false;

        try {
            /*
             * 최대 5초 동안 락 획득을 기다립니다.
             * 락을 획득하면 최대 10초 동안 유지합니다.
             *
             * waitTime = 5초
             * leaseTime = 10초
             */
            acquired = lock.tryLock(5, 10, TimeUnit.SECONDS);

            /*
             * 5초 안에 락을 획득하지 못한 경우입니다.
             */
            if (!acquired) {
                throw new IllegalStateException("수강신청 락을 획득하지 못했습니다.");
            }

            /*
             * Redis 락을 획득한 요청만
             * 실제 수강신청 로직을 실행합니다.
             */
            return enrollmentService.enroll(studentId,courseId);

        } finally {
            /*
             * 현재 스레드가 락을 실제로 획득한 경우에만 해제합니다.
             *
             * 락을 획득하지 못한 스레드가 unlock()을 실행하면
             * 예외가 발생할 수 있으므로 반드시 확인합니다.
             */
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}