package com.jiyun.classenrollment.enrollment.application;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/*
 * 수강신청에 JVM 내부 락을 적용하는 클래스입니다.
 *
 * 같은 과목의 요청은 한 번에 하나씩 처리하고,
 * 다른 과목의 요청은 동시에 처리할 수 있습니다.
 */
@Component
public class EnrollmentSynchronizedFacade {

    /*
     * 실제 수강신청 업무와 DB 트랜잭션을 처리하는 서비스입니다.
     */
    private final EnrollmentService enrollmentService;

    /*
     * 과목별로 사용할 락 객체를 저장합니다.
     *
     * 예:
     * 1번 과목 → 락 객체 A
     * 2번 과목 → 락 객체 B
     *
     * ConcurrentHashMap:
     * 여러 스레드가 동시에 접근해도 안전한 Map입니다.
     */
    private final Map<Long, Object> courseLocks =
            new ConcurrentHashMap<>();


    /*
     * Spring이 EnrollmentService 객체를 주입합니다.
     */
    public EnrollmentSynchronizedFacade(
            EnrollmentService enrollmentService
    ) {
        this.enrollmentService = enrollmentService;
    }


    public Long enroll(Long studentId, Long courseId) {

        /*
         * courseId에 해당하는 락 객체를 가져옵니다.
         *
         * computeIfAbsent():
         * 해당 과목의 락이 이미 있으면 기존 객체를 반환하고,
         * 없으면 새로운 Object를 생성해 Map에 저장합니다.
         *
         * 예:
         * courseId = 2
         * courseLocks에 2번 과목이 없음
         * → 새로운 Object 생성
         * → courseLocks에 저장
         */
        Object courseLock = courseLocks.computeIfAbsent(
                courseId,
                key -> new Object()
        );


        /*
         * 같은 courseLock을 사용하는 스레드는
         * synchronized 블록에 한 번에 하나만 들어올 수 있습니다.
         *
         * 2번 과목 요청 5개:
         * 모두 같은 courseLock 사용
         * → 한 요청만 실행
         * → 나머지 요청은 앞 요청이 끝날 때까지 대기
         *
         * 1번 과목 요청과 2번 과목 요청:
         * 서로 다른 courseLock 사용
         * → 동시에 실행 가능
         */
        synchronized (courseLock) {

            /*
             * 락을 획득한 요청만 실제 수강신청을 실행합니다.
             *
             * EnrollmentService.enroll()에는 @Transactional이 있으므로:
             *
             * 트랜잭션 시작
             * → 수강신청 처리
             * → DB 커밋
             * → 메서드 반환
             *
             * 그 후 synchronized 블록을 빠져나가면서 락이 해제됩니다.
             */
            return enrollmentService.enroll(
                    studentId,
                    courseId
            );
        }
    }
}