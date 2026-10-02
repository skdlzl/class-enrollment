package com.jiyun.classenrollment.course.domain;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CourseCapacityQueryIntegrationTest {
    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");
    @Autowired
    private CourseRepository courses;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void 관리중인_과목객체가_있어도_스칼라조회는_DB변경을_읽는다() {
        jdbc.update("UPDATE courses SET capacity = 100, enrolled_count = 99 WHERE id = 1");
        Course cached = courses.findById(1L).orElseThrow();
        assertFalse(cached.isFull());
        assertEquals(false, courses.findFullStatusById(1L).orElseThrow());
        jdbc.update("UPDATE courses SET enrolled_count = 100 WHERE id = 1");
        assertEquals(true, courses.findFullStatusById(1L).orElseThrow());
        assertFalse(cached.isFull());
    }

    @Test
    void 존재하지_않는_과목은_빈결과를_반환한다() {
        assertTrue(courses.findFullStatusById(Long.MAX_VALUE).isEmpty());
    }
}
