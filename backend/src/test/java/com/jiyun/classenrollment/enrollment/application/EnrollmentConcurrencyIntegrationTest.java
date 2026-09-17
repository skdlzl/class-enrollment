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
        Long enrollmentId = enrollmentService.enroll(2L, 1L);

        Long enrollmentCount = countEnrollments();
        Integer enrolledCount = findEnrolledCount();

        assertNotNull(enrollmentId);
        assertEquals(1L, enrollmentCount);
        assertEquals(1, enrolledCount);
    }

    @Test
    void 동시에_신청해도_정원을_초과하지_않아야_한다() throws InterruptedException {
        int requestCount = 10;
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
                        enrollmentService.enroll(requestStudentId, 1L);
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

        Long enrollmentCount = countEnrollments();
        Integer enrolledCount = findEnrolledCount();

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

    private Long countEnrollments() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM enrollments WHERE course_id = ?",
                Long.class,
                1L
        );
    }

    private Integer findEnrolledCount() {
        return jdbcTemplate.queryForObject(
                "SELECT enrolled_count FROM courses WHERE id = ?",
                Integer.class,
                1L
        );
    }
}
