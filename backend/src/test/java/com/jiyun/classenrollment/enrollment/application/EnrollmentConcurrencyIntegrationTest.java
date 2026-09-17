package com.jiyun.classenrollment.enrollment.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

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

    @Autowired
    private EnrollmentService enrollmentService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
                        enrollmentService.enroll(requestStudentId, courseId);
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
                        enrollmentService.enroll(requestStudentId, courseId);
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
}
