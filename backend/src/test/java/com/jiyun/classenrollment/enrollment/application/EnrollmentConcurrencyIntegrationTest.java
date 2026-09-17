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
    void 정원이_한자리인_과목을_준비한다() {
        jdbcTemplate.update("DELETE FROM enrollments WHERE course_id = ?", 1L);
        jdbcTemplate.update("""
                UPDATE courses
                SET capacity = 1,
                    enrolled_count = 0
                WHERE id = ?
                """, 1L);
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
