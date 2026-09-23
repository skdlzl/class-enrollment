package com.jiyun.classenrollment.enrollment.application;

import com.jiyun.classenrollment.common.error.EnrollmentException;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

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

    public Long enroll(Long studentId, Long courseId) {
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
             *
             * leaseTime을 직접 지정하지 않았기 때문에
             * 락을 보유하는 동안 Redisson Watchdog가
             * 락 만료 시간을 자동으로 연장합니다.
             */
            acquired = lock.tryLock(5, TimeUnit.SECONDS);

            /*
             * 5초 안에 락을 획득하지 못했다면 서버 내부 오류가 아니라
             * 일시적인 과부하임을 나타내는 HTTP 503을 반환합니다.
             */
            if (!acquired) {
                throw new EnrollmentException(
                        "LOCK_ACQUISITION_TIMEOUT",
                        "요청이 몰려 수강신청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.",
                        HttpStatus.SERVICE_UNAVAILABLE
                );
            }

            /*
             * Redis 락을 획득한 요청만
             * 실제 수강신청 로직을 실행합니다.
             */
            return enrollmentService.enroll(studentId, courseId);

        } catch (InterruptedException exception) {
            /*
             * 락을 기다리던 스레드에 중단 요청이 들어오면
             * 중단 상태를 복원한 뒤 애플리케이션 예외로 변환합니다.
             */
            Thread.currentThread().interrupt();
            throw new IllegalStateException("수강신청 락 대기 중 요청이 중단되었습니다.", exception);

        } finally {
            /*
             * 현재 스레드가 락을 실제로 획득한 경우에만 해제합니다.
             */
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
