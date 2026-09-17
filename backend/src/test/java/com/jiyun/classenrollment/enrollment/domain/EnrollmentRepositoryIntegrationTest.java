package com.jiyun.classenrollment.enrollment.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.DayOfWeek;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EnrollmentRepositoryIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql =
            new MySQLContainer<>("mysql:8.4");

    @Autowired
    private EnrollmentRepository enrollmentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void 기존수강신청을_준비한다() {
        // 학생 1이 월요일 09:00~10:30 수업인 과목 1을 신청한 상태를 만든다.
        jdbcTemplate.update("""
                INSERT INTO enrollments (student_id, course_id)
                VALUES (?, ?)
                """, 1L, 1L);
    }

    @Test
    void 기존수업과_신규수업_시간이_겹치면_충돌건수를_반환한다() {
        long conflicts = enrollmentRepository.countScheduleConflicts(
                1L,
                DayOfWeek.MONDAY,
                LocalTime.of(10, 0),
                LocalTime.of(12, 0)
        );

        assertEquals(1L, conflicts);
    }

    @Test
    void 기존수업_종료시간과_신규수업_시작시간이_같으면_충돌하지_않는다() {
        long conflicts = enrollmentRepository.countScheduleConflicts(
                1L,
                DayOfWeek.MONDAY,
                LocalTime.of(10, 30),
                LocalTime.of(12, 0)
        );

        assertEquals(0L, conflicts);
    }
}
