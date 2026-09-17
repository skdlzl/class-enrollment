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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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

        Long enrollmentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM enrollments WHERE course_id = ?",
                Long.class,
                1L
        );
        Integer enrolledCount = jdbcTemplate.queryForObject(
                "SELECT enrolled_count FROM courses WHERE id = ?",
                Integer.class,
                1L
        );

        assertNotNull(enrollmentId);
        assertEquals(1L, enrollmentCount);
        assertEquals(1, enrolledCount);
    }
}
