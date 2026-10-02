package com.jiyun.classenrollment.enrollment.application;

import com.jiyun.classenrollment.common.error.EnrollmentException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.redisson.Redisson;
import org.redisson.config.Config;
import java.util.concurrent.*;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest
class EnrollmentCourseAdmissionIntegrationTest {
    @Container @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    static String redisAddress() { return "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379); }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) { registry.add("redisson.address", EnrollmentCourseAdmissionIntegrationTest::redisAddress); }
    @Autowired EnrollmentService service;
    @Autowired JdbcTemplate jdbc;

    @Test
    void independentGatesAndRedisClientsKeepCapacityAndRejectFullCourse() throws Exception {
        jdbc.update("DELETE FROM enrollments WHERE course_id=1");
        jdbc.update("UPDATE courses SET capacity=10,enrolled_count=0 WHERE id=1");
        for (int id=10000; id<10050; id++) jdbc.update(
                "INSERT INTO students(id,student_number,name,status,max_credits) VALUES(?,?,?,'ACTIVE',18)",
                id, "ADMISSION"+id, "test"+id);
        Config configA = new Config(), configB = new Config();
        configA.useSingleServer().setAddress(redisAddress());
        configB.useSingleServer().setAddress(redisAddress());
        var clientA = Redisson.create(configA);
        var clientB = Redisson.create(configB);
        ExecutorService pool = Executors.newFixedThreadPool(50);
        try {
            var facadeA = new EnrollmentRedissonFacade(service, clientA, true);
            var facadeB = new EnrollmentRedissonFacade(service, clientB, true);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> results = new ArrayList<>();
            for (long id=10000; id<10050; id++) {
                long studentId=id;
                var facade = id%2==0 ? facadeA : facadeB;
                results.add(pool.submit(() -> {
                    start.await();
                    try { facade.enroll(studentId, 1L); return "201"; }
                    catch (EnrollmentException e) { return e.getCode(); }
                }));
            }
            start.countDown();
            int success=0, full=0;
            for (var result:results) {
                String outcome=result.get(30, TimeUnit.SECONDS);
                if (outcome.equals("201")) success++;
                else if (outcome.equals("COURSE_FULL")) full++;
                else fail("Unexpected result: "+outcome);
            }
            assertEquals(10, success);
            assertEquals(40, full);
            assertEquals(10, jdbc.queryForObject("SELECT enrolled_count FROM courses WHERE id=1", Integer.class));
            assertEquals(10L, jdbc.queryForObject("SELECT COUNT(*) FROM enrollments WHERE course_id=1", Long.class));
            assertThrows(EnrollmentException.class, () -> service.validateCourseCapacity(1L));
            // A fresh scalar read must see newly freed seats, not cache a permanent full flag.
            jdbc.update("DELETE FROM enrollments WHERE course_id=1");
            jdbc.update("UPDATE courses SET enrolled_count=0 WHERE id=1");
            assertDoesNotThrow(() -> service.validateCourseCapacity(1L));
        } finally {
            pool.shutdownNow(); clientA.shutdown(); clientB.shutdown();
        }
    }
}
