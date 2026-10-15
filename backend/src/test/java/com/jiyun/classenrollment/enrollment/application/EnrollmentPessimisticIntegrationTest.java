package com.jiyun.classenrollment.enrollment.application;

import com.jiyun.classenrollment.common.error.EnrollmentException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "enrollment.course-lock-strategy=PESSIMISTIC",
        "enrollment.local-gate-enabled=true",
        "experiment.enabled=true",
        "spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout=1"
})
class EnrollmentPessimisticIntegrationTest {
    @Container @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379);
    static String address() { return "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379); }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("redisson.address", EnrollmentPessimisticIntegrationTest::address);
    }
    @Autowired EnrollmentService service;
    @Autowired EnrollmentRedissonFacade facade;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManager em;
    @Autowired MockMvc mvc;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM enrollments");
        jdbc.update("UPDATE courses SET capacity=5,enrolled_count=0");
        jdbc.update("DELETE FROM students WHERE id BETWEEN 1000 AND 1019");
        for (int id=1000; id<1020; id++) {
            jdbc.update("INSERT INTO students(id,student_number,name,status,max_credits) VALUES(?,?,?,'ACTIVE',18)",
                    id, "PESS"+id, "test"+id);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(CourseLockStrategy.class)
    void independentClientsWithLocalGatePreserveCapacity(CourseLockStrategy strategy) throws Exception {
        var aConfig = new Config(); aConfig.useSingleServer().setAddress(address());
        var bConfig = new Config(); bConfig.useSingleServer().setAddress(address());
        var a = Redisson.create(aConfig); var b = Redisson.create(bConfig);
        var pool = Executors.newFixedThreadPool(20);
        try {
            var serverA = new EnrollmentRedissonFacade(service, a, strategy);
            var serverB = new EnrollmentRedissonFacade(service, b, strategy);
            org.springframework.test.util.ReflectionTestUtils.setField(serverA, "localGateEnabled", true);
            org.springframework.test.util.ReflectionTestUtils.setField(serverB, "localGateEnabled", true);
            var ready = new CountDownLatch(20); var start = new CountDownLatch(1);
            List<Future<String>> results = new ArrayList<>();
            for (long id=1000; id<1020; id++) {
                long studentId = id;
                var target = id%2 == 0 ? serverA : serverB;
                results.add(pool.submit(() -> {
                    ready.countDown(); start.await();
                    return outcome(target, studentId, 1L);
                }));
            }
            boolean allReady = ready.await(10, TimeUnit.SECONDS);
            start.countDown(); assertTrue(allReady);
            List<String> outcomes = new ArrayList<>();
            for (var result:results) outcomes.add(result.get(30, TimeUnit.SECONDS));
            assertEquals(5, outcomes.stream().filter("SUCCESS"::equals).count());
            assertEquals(15, outcomes.stream().filter("COURSE_FULL"::equals).count());
            assertCourse(1, 5);
        } finally { pool.shutdownNow(); a.shutdown(); b.shutdown(); }
    }

    @Test
    void retainedStudentLockPreventsConcurrentCreditOverflow() throws Exception {
        jdbc.update("UPDATE students SET max_credits=3 WHERE id=1000");
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var a = pool.submit(() -> { start.await(); return outcome(facade, 1000L, 1L); });
            var b = pool.submit(() -> { start.await(); return outcome(facade, 1000L, 2L); });
            start.countDown();
            var outcomes = List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
            assertTrue(outcomes.contains("SUCCESS"));
            assertTrue(outcomes.contains("CREDIT_LIMIT_EXCEEDED"));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM enrollments WHERE student_id=1000", Integer.class));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void rollbackUndoesInsertAndCounterAndReleasesRowLock() {
        var tx = new TransactionTemplate(transactions);
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            service.completeEnrollmentWithPessimisticLock(1000L, 1L);
            em.flush(); // Execute INSERT and counter UPDATE before injected failure.
            throw new IllegalStateException("injected failure after flush");
        }));
        assertCourse(1, 0);
        assertNotNull(facade.enroll(1001L, 1L));
        assertCourse(1, 1);
    }

    @Test
    void realDatabaseTimeoutReturns503AndNextHttpRequestCanSucceed() throws Exception {
        try (var holder = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
            holder.setAutoCommit(false);
            try (var statement = holder.createStatement();
                 var row = statement.executeQuery("SELECT id FROM courses WHERE id=1 FOR UPDATE")) {
                assertTrue(row.next());
                mvc.perform(post("/api/enrollments").contentType("application/json")
                        .content("{\"studentId\":1000,\"courseId\":1}"))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(jsonPath("$.code").value("DB_LOCK_FAILURE"));
                assertCourse(1, 0);
            } finally { holder.rollback(); }
        }
        mvc.perform(post("/api/enrollments").contentType("application/json")
                .content("{\"studentId\":1000,\"courseId\":1}"))
                .andExpect(status().isCreated());
        assertCourse(1, 1);
    }

    @Test
    void experimentReadActuallyQueriesDatabaseAndPoolReportsMode() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/experiment/read"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(1));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/experiment/pool"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.strategy").value("PESSIMISTIC"))
                .andExpect(jsonPath("$.gate").value(true)).andExpect(jsonPath("$.waiting").isNumber());
    }

    private String outcome(EnrollmentRedissonFacade target, long student, long course) {
        try { target.enroll(student, course); return "SUCCESS"; }
        catch (EnrollmentException e) { return e.getCode(); }
    }
    private void assertCourse(long courseId, int expected) {
        assertEquals(expected, jdbc.queryForObject("SELECT COUNT(*) FROM enrollments WHERE course_id=?", Integer.class, courseId));
        assertEquals(expected, jdbc.queryForObject("SELECT enrolled_count FROM courses WHERE id=?", Integer.class, courseId));
    }
}
