package com.jiyun.classenrollment.enrollment.application;

import com.jiyun.classenrollment.common.error.EnrollmentException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.redisson.api.RedissonClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@SpringBootTest
class EnrollmentConcurrencyIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql =
            new MySQLContainer<>("mysql:8.4");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7.4-alpine")
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {

        registry.add(
                "redisson.address",
                () -> "redis://"
                        + redis.getHost()
                        + ":"
                        + redis.getMappedPort(6379)
        );
    }

    @Autowired
    private EnrollmentService enrollmentService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 새로 추가
    @Autowired
    private EnrollmentSynchronizedFacade enrollmentSynchronizedFacade;

    @Autowired
    private RedissonClient redissonClient;

    private EnrollmentRedissonFacade redissonServerA;
    private EnrollmentRedissonFacade redissonServerB;

    // 서버 2대의 서로 다른 메모리를 흉내 내는 객체
    private EnrollmentSynchronizedFacade serverA;
    private EnrollmentSynchronizedFacade serverB;

    @BeforeEach
    void 테스트데이터를_준비한다() {
        jdbcTemplate.update(
                "DELETE FROM enrollments WHERE course_id = ? OR student_id BETWEEN ? AND ?",
                1L, 100L, 109L
        );
        jdbcTemplate.update(
                "DELETE FROM enrollments WHERE course_id = ?",
                2L
        );
        jdbcTemplate.update(
                "DELETE FROM students WHERE id BETWEEN ? AND ?",
                100L, 109L
        );
        jdbcTemplate.update("""
                UPDATE courses
                SET capacity = 1,
                    enrolled_count = 0
                WHERE id = ?
                """, 1L);

        for (long studentId = 100L; studentId <= 109L; studentId++) {
            jdbcTemplate.update("""
                            INSERT INTO students (
                                id,
                                student_number,
                                name,
                                status,
                                max_credits
                            )
                            VALUES (?, ?, ?, 'ACTIVE', 18)
                            """,
                    studentId,
                    "TEST" + studentId,
                    "동시신청학생" + studentId
            );
        }

        // 서버 2대 테스트 : 각각 독립적인 courseLocks Map을 가진 객체 생성
        serverA = new EnrollmentSynchronizedFacade(enrollmentService);
        serverB = new EnrollmentSynchronizedFacade(enrollmentService);

        //redis 테스트
        redissonServerA = new EnrollmentRedissonFacade(enrollmentService, redissonClient);
        redissonServerB = new EnrollmentRedissonFacade(enrollmentService, redissonClient);
    }

    @Test
    void 한명이_신청하면_정원과_신청내역이_정상적으로_증가한다() {
        Long courseId = 1L;
        Long enrollmentId = enrollmentService.enroll(2L, courseId);

        Long enrollmentCount = countEnrollments(courseId);
        Integer enrolledCount = findEnrolledCount(courseId);

        assertNotNull(enrollmentId);
        assertEquals(1L, enrollmentCount);
        assertEquals(1, enrolledCount);
    }

    @Test
    void 동시에_신청해도_정원을_초과하지_않아야_한다() throws InterruptedException {
        int requestCount = 10;
        Long courseId = 1L;
        ExecutorService executorService = Executors.newFixedThreadPool(requestCount);

        CountDownLatch readyLatch = new CountDownLatch(requestCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(requestCount);

        AtomicInteger successCount = new AtomicInteger();
        List<Throwable> failures = new CopyOnWriteArrayList<>();

        try {
            for (long studentId = 100L; studentId <= 109L; studentId++) {
                long requestStudentId = studentId;

                executorService.submit(() -> {
                    readyLatch.countDown();

                    try {
                        startLatch.await();
                        //enrollmentService.enroll(requestStudentId, courseId);
                        enrollmentSynchronizedFacade.enroll(requestStudentId, courseId);
                        successCount.incrementAndGet();
                    } catch (Throwable throwable) {
                        failures.add(throwable);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            assertTrue(readyLatch.await(10, TimeUnit.SECONDS),
                    "모든 요청이 제한 시간 안에 준비되어야 합니다.");

            startLatch.countDown();

            assertTrue(doneLatch.await(30, TimeUnit.SECONDS),
                    "모든 요청이 제한 시간 안에 완료되어야 합니다.");
        } finally {
            startLatch.countDown();
            executorService.shutdownNow();
        }

        Long enrollmentCount = countEnrollments(courseId);
        Integer enrolledCount = findEnrolledCount(courseId);

        System.out.printf(
                "성공 요청=%d, 실패 요청=%d, 신청 내역=%d, enrolled_count=%d%n",
                successCount.get(),
                failures.size(),
                enrollmentCount,
                enrolledCount
        );

        assertEquals(1L, enrollmentCount,
                "정원이 1명이므로 신청 내역은 1건이어야 합니다.");
        assertEquals(1, enrolledCount,
                "과목의 신청 인원은 정원 1명을 초과하면 안 됩니다.");
    }

    @Test
    void 정원_두자리에_다섯명이_동시에_신청하면_두명만_성공해야_한다() throws InterruptedException {
        Long courseId = 2L;
        // 이 테스트에서만 2번 과목의 정원을 2명으로 변경
        jdbcTemplate.update("""
                UPDATE courses
                SET capacity = 2,
                    enrolled_count = 0
                WHERE id = ?
                """, courseId);


        int requestCount = 5;

        ExecutorService executorService = Executors.newFixedThreadPool(requestCount);

        CountDownLatch readyLatch = new CountDownLatch(requestCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(requestCount);

        AtomicInteger successCount = new AtomicInteger();
        List<Throwable> failures = new CopyOnWriteArrayList<>();

        try {
            for (long studentId = 100L; studentId <= 104L; studentId++) {
                long requestStudentId = studentId;

                executorService.submit(() -> {
                    readyLatch.countDown();

                    try {
                        startLatch.await();
                        //enrollmentService.enroll(requestStudentId, courseId);
                        enrollmentSynchronizedFacade.enroll(requestStudentId, courseId);
                        successCount.incrementAndGet();
                    } catch (Throwable throwable) {
                        failures.add(throwable);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            assertTrue(readyLatch.await(10, TimeUnit.SECONDS),
                    "모든 요청이 제한 시간 안에 준비되어야 합니다.");

            startLatch.countDown();

            assertTrue(doneLatch.await(30, TimeUnit.SECONDS),
                    "모든 요청이 제한 시간 안에 완료되어야 합니다.");
        } finally {
            startLatch.countDown();
            executorService.shutdownNow();
        }

        Long enrollmentCount = countEnrollments(courseId);
        Integer enrolledCount = findEnrolledCount(courseId);


        System.out.printf(
                "성공 요청=%d, 실패 요청=%d, 신청 내역=%d, enrolled_count=%d%n",
                successCount.get(),
                failures.size(),
                enrollmentCount,
                enrolledCount
        );
        // MySQL이 기록한 가장 최근 데드락 정보를 콘솔에 출력
        //최근_데드락정보를_출력한다();

        List<Long> enrolledStudentIds = jdbcTemplate.queryForList(
                """
                        SELECT student_id
                        FROM enrollments
                        WHERE course_id = ?
                        ORDER BY student_id
                        """,
                Long.class,
                courseId
        );

        System.out.println("실제 신청 성공 학생=" + enrolledStudentIds);

        failures.forEach(throwable -> {
            Throwable rootCause = throwable;

            while (rootCause.getCause() != null) {
                rootCause = rootCause.getCause();
            }

            System.out.printf(
                    "실패 예외=%s, 원인=%s%n",
                    rootCause.getClass().getName(),
                    rootCause.getMessage()
            );
        });

        assertEquals(2, successCount.get(),
                "정원이 2명이므로 성공 요청은 2건이어야 합니다.");

        assertEquals(3, failures.size(),
                "전체 5건 중 3건은 정원 초과로 실패해야 합니다.");

        assertEquals(2L, enrollmentCount,
                "정원이 2명이므로 신청 내역은 2건이어야 합니다.");

        assertEquals(2, enrolledCount,
                "과목의 신청 인원은 정원 2명을 초과하면 안 됩니다.");
    }

    private Long countEnrollments(Long courseId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM enrollments WHERE course_id = ?",
                Long.class,
                courseId
        );
    }

    private Integer findEnrolledCount(Long courseId) {
        return jdbcTemplate.queryForObject(
                "SELECT enrolled_count FROM courses WHERE id = ?",
                Integer.class,
                courseId
        );
    }

    private void 최근_데드락정보를_출력한다() {
        /*
         * SHOW ENGINE INNODB STATUS는 관리자 권한이 필요할 수 있어서
         * Testcontainers MySQL의 root 계정으로 별도 접속합니다.
         */
        try (
                Connection connection = DriverManager.getConnection(
                        mysql.getJdbcUrl(),
                        "root",
                        mysql.getPassword()
                );

                Statement statement = connection.createStatement();

                ResultSet resultSet = statement.executeQuery(
                        "SHOW ENGINE INNODB STATUS"
                )
        ) {
            if (resultSet.next()) {
                String innodbStatus = resultSet.getString("Status");

                /*
                 * 전체 InnoDB 상태는 매우 길기 때문에
                 * 최근 데드락 부분부터 출력합니다.
                 */
                int deadlockPosition =
                        innodbStatus.lastIndexOf("LATEST DETECTED DEADLOCK");

                if (deadlockPosition >= 0) {
                    System.out.println(
                            innodbStatus.substring(deadlockPosition)
                    );
                } else {
                    System.out.println(
                            "MySQL에서 최근 데드락 정보를 찾지 못했습니다."
                    );
                }
            }
        } catch (Exception exception) {
            System.out.println(
                    "데드락 정보 조회 실패: " + exception.getMessage()
            );
        }
    }

    @Disabled("synchronized가 여러 서버에서 락을 공유하지 못하는 현상을 기록한 실패 재현 테스트")
    @Test
    void 서버가_두대면_synchronized는_락을_공유하지_못한다() throws InterruptedException {
        // 테스트할 과목은 2번 과목
        Long courseId = 2L;

        // 동시에 요청할 학생 수
        int requestCount = 5;

        // 2번 과목을 정원 2명, 현재 신청자 0명으로 초기화
        jdbcTemplate.update("""
                UPDATE courses
                SET capacity = 2,
                    enrolled_count = 0
                WHERE id = ?
                """, courseId);

        // 5개 요청을 동시에 처리할 스레드 풀
        ExecutorService executorService = Executors.newFixedThreadPool(requestCount);

        // 다섯 스레드가 모두 준비됐는지 확인
        CountDownLatch readyLatch = new CountDownLatch(requestCount);

        // 모든 스레드를 동시에 출발시키는 신호
        CountDownLatch startLatch = new CountDownLatch(1);

        // 다섯 스레드가 모두 끝났는지 확인
        CountDownLatch doneLatch = new CountDownLatch(requestCount);

        // 정상적으로 신청된 요청 수
        AtomicInteger successCount = new AtomicInteger();

        // 실패한 요청의 예외를 저장
        List<Throwable> failures = new CopyOnWriteArrayList<>();

        try {
            // 학생 100번부터 104번까지 총 5명의 요청을 생성
            for (long studentId = 100L; studentId <= 104L; studentId++) {

                /*
                 * 람다식 내부에서 사용할 학생 ID를 별도 변수에 저장합니다.
                 *
                 * 각 스레드가 자신의 학생 ID를 사용하도록 하기 위함입니다.
                 */
                long requestStudentId = studentId;

                /*
                 * 스레드 풀에 수강신청 작업을 제출합니다.
                 *
                 * submit()을 호출하는 즉시 이 코드가 반드시 실행되는 것은 아니고,
                 * 스레드 풀이 작업을 받아 실행합니다.
                 */
                executorService.submit(() -> {

                    // 현재 스레드가 실행 준비를 마쳤다고 표시
                    readyLatch.countDown();

                    try {
                        /*
                         * startLatch 값이 0이 될 때까지 대기합니다.
                         *
                         * 다섯 스레드를 최대한 동시에 출발시키기 위한 대기선입니다.
                         */
                        startLatch.await();

                        /*
                         * 짝수 학생은 serverA,
                         * 홀수 학생은 serverB에서 처리합니다.
                         *
                         * 실제 환경에서 로드 밸런서가 요청을
                         * 서로 다른 서버로 분배하는 상황을 흉내 냅니다.
                         */
                        if (requestStudentId % 2 == 0) {
                            serverA.enroll(requestStudentId, courseId);
                        } else {
                            serverB.enroll(requestStudentId, courseId);
                        }

                        // 예외 없이 신청이 끝났다면 성공 횟수를 1 증가
                        successCount.incrementAndGet();

                    } catch (Throwable throwable) {
                        // 신청 도중 발생한 예외를 스레드 안전한 List에 저장
                        failures.add(throwable);

                    } finally {
                        // 성공과 실패에 관계없이 작업 완료 횟수를 1 감소
                        doneLatch.countDown();
                    }
                });
            }

            /*
             * 5개의 스레드가 모두 readyLatch.countDown()을 실행할 때까지 기다립니다.
             *
             * 10초 안에 준비되지 않으면 테스트가 실패합니다.
             */
            assertTrue(
                    readyLatch.await(10, TimeUnit.SECONDS),
                    "모든 요청이 제한 시간 안에 준비되어야 합니다."
            );

            /*
             * startLatch: 1 → 0
             *
             * startLatch.await()에서 대기 중인 스레드들이
             * 동시에 수강신청을 시작합니다.
             */
            startLatch.countDown();

            /*
             * 모든 작업이 doneLatch.countDown()을 실행할 때까지 기다립니다.
             *
             * 30초 안에 끝나지 않으면 테스트가 실패합니다.
             */
            assertTrue(
                    doneLatch.await(30, TimeUnit.SECONDS),
                    "모든 요청이 제한 시간 안에 완료되어야 합니다."
            );

        } finally {
            startLatch.countDown();
            executorService.shutdownNow();
        }

        Long enrollmentCount = countEnrollments(courseId);
        Integer enrolledCount = findEnrolledCount(courseId);

        System.out.printf(
                "성공 요청=%d, 실패 요청=%d, 신청 내역=%d, enrolled_count=%d%n",
                successCount.get(),
                failures.size(),
                enrollmentCount,
                enrolledCount
        );

        failures.forEach(throwable ->
                System.out.printf(
                        "실패 예외=%s, 원인=%s%n",
                        throwable.getClass().getName(),
                        throwable.getMessage()
                )
        );

        assertEquals(
                2,
                successCount.get(),
                "정원이 2명이므로 성공 요청은 2건이어야 합니다."
        );

        assertEquals(
                2L,
                enrollmentCount,
                "정원이 2명이므로 신청 내역은 2건이어야 합니다."
        );

        assertEquals(
                2,
                enrolledCount,
                "과목의 신청 인원은 정원을 초과하면 안 됩니다."
        );
    }

    @Test
    void 서버가_두대여도_Redisson으로_정원을_정확히_보장한다() throws InterruptedException {

        Long courseId = 2L;
        int requestCount = 5;

        // 2번 과목의 정원을 2명으로 초기화
        jdbcTemplate.update("""
                UPDATE courses
                SET capacity = 2,
                    enrolled_count = 0
                WHERE id = ?
                """, courseId);

        ExecutorService executorService = Executors.newFixedThreadPool(requestCount);
        CountDownLatch readyLatch = new CountDownLatch(requestCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(requestCount);
        AtomicInteger successCount = new AtomicInteger();
        List<Throwable> failures = new CopyOnWriteArrayList<>();

        try {
            for (long studentId = 100L; studentId <= 104L; studentId++) {
                long requestStudentId = studentId;

                executorService.submit(() -> {
                    readyLatch.countDown();

                    try {
                        startLatch.await();

                        /*
                         * 짝수 학생은 서버 A,
                         * 홀수 학생은 서버 B로 요청합니다.
                         *
                         * 두 객체 모두 Redis의
                         * lock:course:2를 사용합니다.
                         */
                        if (requestStudentId % 2 == 0) {
                            redissonServerA.enroll(
                                    requestStudentId,
                                    courseId
                            );
                        } else {
                            redissonServerB.enroll(
                                    requestStudentId,
                                    courseId
                            );
                        }

                        successCount.incrementAndGet();

                    } catch (Throwable throwable) {
                        failures.add(throwable);

                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            assertTrue(
                    readyLatch.await(10, TimeUnit.SECONDS),
                    "모든 요청이 제한 시간 안에 준비되어야 합니다."
            );

            // 5개 요청 동시 출발
            startLatch.countDown();

            assertTrue(
                    doneLatch.await(30, TimeUnit.SECONDS),
                    "모든 요청이 제한 시간 안에 완료되어야 합니다."
            );

        } finally {
            startLatch.countDown();
            executorService.shutdownNow();
        }

        Long enrollmentCount = countEnrollments(courseId);
        Integer enrolledCount = findEnrolledCount(courseId);

        System.out.printf(
                "Redisson 결과: 성공=%d, 실패=%d, 신청 내역=%d, enrolled_count=%d%n",
                successCount.get(),
                failures.size(),
                enrollmentCount,
                enrolledCount
        );

        failures.forEach(throwable ->
                System.out.printf(
                        "실패 예외=%s, 원인=%s%n",
                        throwable.getClass().getName(),
                        throwable.getMessage()
                )
        );

        // 정원이 2명이므로 정확히 2명만 성공해야 함
        assertEquals(
                2,
                successCount.get(),
                "정원이 2명이므로 성공 요청은 2건이어야 합니다."
        );

        // 실제 신청 내역도 정확히 2건이어야 함
        assertEquals(
                2L,
                enrollmentCount,
                "정원이 2명이므로 신청 내역은 2건이어야 합니다."
        );

        // 과목의 집계 인원도 정확히 2명이어야 함
        assertEquals(
                2,
                enrolledCount,
                "과목의 신청 인원은 2명이어야 합니다."
        );

        // 나머지 3명은 정상적으로 정원 마감 처리돼야 함
        assertEquals(
                3,
                failures.size(),
                "정원 마감으로 3건이 실패해야 합니다."
        );

        assertTrue(
                failures.stream().allMatch(throwable ->
                        throwable instanceof EnrollmentException
                                && "COURSE_FULL".equals(
                                ((EnrollmentException) throwable).getCode()
                        )
                ),
                "실패한 요청은 모두 COURSE_FULL 예외여야 합니다."
        );
    }

    @Disabled("고정 leaseTime 만료로 동시 실행되는 위험 재현")
    @Test
    void 고정_leaseTime보다_작업이_길면_두_요청이_동시에_실행된다()
            throws Exception {

        String lockKey = "test:lock:lease-expiration";

        // 이전 테스트에서 락이 남아 있을 가능성을 제거
        redissonClient.getLock(lockKey).forceUnlock();

        ExecutorService executorService = Executors.newFixedThreadPool(2);

        // 첫 번째 스레드가 락을 획득했는지 확인
        CountDownLatch firstLockAcquired = new CountDownLatch(1);
        // 현재 임계영역에서 실행 중인 스레드 수
        AtomicInteger concurrentCount = new AtomicInteger();
        // 동시에 실행된 최대 스레드 수
        AtomicInteger maxConcurrentCount = new AtomicInteger();

        try {
            Future<?> firstRequest = executorService.submit(() -> {

                RLock lock = redissonClient.getLock(lockKey);
                boolean acquired = false;
                boolean entered = false;

                try {
                    /*
                     * 락 획득 대기: 최대 1초
                     * 락 유지 시간: 10초
                     */
                    acquired = lock.tryLock(
                            1,
                            10,
                            TimeUnit.SECONDS
                    );

                    if (!acquired) {
                        throw new IllegalStateException("첫 번째 요청이 락을 획득하지 못했습니다.");
                    }

                    firstLockAcquired.countDown();

                    int current = concurrentCount.incrementAndGet();

                    entered = true;

                    maxConcurrentCount.accumulateAndGet(
                            current,
                            Math::max
                    );

                    /*
                     * 실제 작업이 오래 걸리는 상황을 재현합니다.
                     *
                     * leaseTime은 10초인데 작업은 15초이므로
                     * 작업이 끝나기 전에 Redis 락이 만료됩니다.
                     */
                    Thread.sleep(15_000);

                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(exception);

                } finally {
                    if (entered) {
                        concurrentCount.decrementAndGet();
                    }

                    /*
                     * 10초 후 락이 이미 만료됐을 수 있으므로
                     * 현재 스레드가 보유한 경우에만 해제합니다.
                     */
                    if (acquired && lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }
            });

            // 첫 번째 요청이 락을 획득할 때까지 대기
            assertTrue(
                    firstLockAcquired.await(5, TimeUnit.SECONDS),
                    "첫 번째 요청이 락을 획득해야 합니다."
            );

            Future<?> secondRequest = executorService.submit(() -> {

                RLock lock = redissonClient.getLock(lockKey);
                boolean acquired = false;
                boolean entered = false;

                try {
                    /*
                     * 첫 번째 락이 10초 후 만료되면
                     * 두 번째 요청이 락을 획득합니다.
                     */
                    acquired = lock.tryLock(
                            15,
                            10,
                            TimeUnit.SECONDS
                    );

                    if (!acquired) {
                        throw new IllegalStateException(
                                "두 번째 요청이 락을 획득하지 못했습니다."
                        );
                    }

                    int current =
                            concurrentCount.incrementAndGet();

                    entered = true;

                    maxConcurrentCount.accumulateAndGet(
                            current,
                            Math::max
                    );

                    // 첫 번째 요청과 겹치는 시간을 확보
                    Thread.sleep(3_000);

                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(exception);

                } finally {
                    if (entered) {
                        concurrentCount.decrementAndGet();
                    }

                    if (acquired && lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }
            });

            firstRequest.get();
            secondRequest.get();

        } finally {
            executorService.shutdownNow();
            redissonClient.getLock(lockKey).forceUnlock();
        }

        System.out.printf(
                "동시에 실행된 최대 요청 수=%d%n",
                maxConcurrentCount.get()
        );

        /*
         * 분산 락이 작업 종료까지 유지됐다면 1이어야 합니다.
         *
         * 하지만 leaseTime이 먼저 만료되므로 실제 결과는 2가 되어
         * 이 테스트가 의도적으로 실패합니다.
         */
        assertEquals(
                1,
                maxConcurrentCount.get(),
                "분산 락 내부에는 한 번에 한 요청만 들어와야 합니다."
        );
    }

    @Test
    void Watchdog는_작업이_길어져도_락을_유지한다() throws Exception {
        String lockKey = "test:lock:watchdog";

        // 이전 테스트에서 같은 이름의 락이 남아 있다면 제거합니다.
        redissonClient.getLock(lockKey).forceUnlock();

        // 두 요청을 서로 다른 스레드에서 실행합니다.
        ExecutorService executorService = Executors.newFixedThreadPool(2);

        // 첫 번째 요청이 락을 획득할 때까지 두 번째 요청의 제출을 기다립니다.
        CountDownLatch firstLockAcquired = new CountDownLatch(1);

        // 현재 임계영역 안에서 실행 중인 요청 수입니다.
        AtomicInteger concurrentCount = new AtomicInteger();

        // 테스트 중 동시에 실행된 요청의 최댓값입니다.
        AtomicInteger maxConcurrentCount = new AtomicInteger();

        try {
            Future<?> firstRequest = executorService.submit(() -> {
                RLock lock = redissonClient.getLock(lockKey);
                boolean acquired = false;
                boolean entered = false;

                try {
                    /*
                     * 최대 1초 동안 락 획득을 기다립니다.
                     *
                     * leaseTime을 지정하지 않았으므로
                     * Redisson Watchdog가 락 만료 시간을 자동으로 연장합니다.
                     */
                    acquired = lock.tryLock(1, TimeUnit.SECONDS);

                    if (!acquired) {
                        throw new IllegalStateException(
                                "첫 번째 요청이 락을 획득하지 못했습니다."
                        );
                    }

                    // 첫 번째 요청이 락을 획득했다고 알립니다.
                    firstLockAcquired.countDown();

                    // 임계영역에 진입한 요청 수를 증가시킵니다.
                    int current = concurrentCount.incrementAndGet();
                    entered = true;

                    // 지금까지의 최대 동시 실행 수를 갱신합니다.
                    maxConcurrentCount.accumulateAndGet(current, Math::max);

                    /*
                     * 첫 번째 작업을 15초 동안 실행합니다.
                     *
                     * 고정 leaseTime 테스트에서는 락이 10초 후 만료됐지만,
                     * 이번에는 Watchdog가 락 만료 시간을 자동 연장합니다.
                     */
                    Thread.sleep(15_000);

                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(exception);

                } finally {
                    if (entered) {
                        concurrentCount.decrementAndGet();
                    }

                    // 현재 스레드가 락을 보유하고 있을 때만 해제합니다.
                    if (acquired && lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }
            });

            /*
             * 첫 번째 요청이 락을 획득할 때까지 기다립니다.
             *
             * 이 과정이 없으면 두 번째 요청이 먼저 락을 획득할 수도 있습니다.
             */
            assertTrue(
                    firstLockAcquired.await(5, TimeUnit.SECONDS),
                    "첫 번째 요청이 락을 획득해야 합니다."
            );

            Future<?> secondRequest = executorService.submit(() -> {
                RLock lock = redissonClient.getLock(lockKey);
                boolean acquired = false;
                boolean entered = false;

                try {
                    /*
                     * 최대 20초 동안 락 획득을 기다립니다.
                     *
                     * 첫 번째 요청이 약 15초 후 락을 반납하면
                     * 두 번째 요청이 이어서 락을 획득합니다.
                     */
                    acquired = lock.tryLock(20, TimeUnit.SECONDS);

                    if (!acquired) {
                        throw new IllegalStateException(
                                "두 번째 요청이 락을 획득하지 못했습니다."
                        );
                    }

                    int current = concurrentCount.incrementAndGet();
                    entered = true;

                    maxConcurrentCount.accumulateAndGet(current, Math::max);

                    // 두 번째 요청이 임계영역에서 작업하는 상황을 표현합니다.
                    Thread.sleep(3_000);

                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(exception);

                } finally {
                    if (entered) {
                        concurrentCount.decrementAndGet();
                    }

                    if (acquired && lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }
            });

            /*
             * Future.get()은 각 작업이 끝날 때까지 기다립니다.
             *
             * 작업 스레드에서 예외가 발생했다면
             * get()을 호출할 때 테스트 스레드로 전달됩니다.
             */
            firstRequest.get();
            secondRequest.get();

        } finally {
            // 테스트가 실패해도 스레드 풀과 Redis 락을 정리합니다.
            executorService.shutdownNow();
            redissonClient.getLock(lockKey).forceUnlock();
        }

        System.out.printf(
                "Watchdog 적용 후 동시에 실행된 최대 요청 수=%d%n",
                maxConcurrentCount.get()
        );

        /*
         * Watchdog가 첫 번째 요청의 락을 계속 연장하므로
         * 두 요청이 임계영역에 동시에 들어가면 안 됩니다.
         */
        assertEquals(
                1,
                maxConcurrentCount.get(),
                "Watchdog가 동작하면 한 번에 한 요청만 실행되어야 합니다."
        );
    }
}
