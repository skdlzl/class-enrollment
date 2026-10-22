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
    final java.nio.file.Path crashLogs = java.nio.file.Path.of("target", "failure-probe-logs",
            java.util.UUID.randomUUID().toString());

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void killedJvmRollsBackOnlyBeforeCommitAndReleasesStudentLease(boolean beforeCommit) throws Exception {
        String stage = beforeCommit ? "BEFORE_COMMIT" : "AFTER_COMMIT";
        java.nio.file.Files.createDirectories(crashLogs);
        var output = crashLogs.resolve(stage + ".log");
        String javaExecutable = java.nio.file.Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        var builder = new ProcessBuilder(javaExecutable, "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                EnrollmentCrashProbe.class.getName(), stage);
        builder.environment().put("DB_URL", mysql.getJdbcUrl());
        builder.environment().put("DB_USERNAME", mysql.getUsername());
        builder.environment().put("DB_PASSWORD", mysql.getPassword());
        builder.environment().put("REDIS_ADDRESS", address());
        builder.redirectErrorStream(true).redirectOutput(output.toFile());
        var process = builder.start();
        var config = new Config(); config.useSingleServer().setAddress(address());
        var observer = Redisson.create(config);
        try {
            long startupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
            while (!java.nio.file.Files.readString(output).contains("CRASH_PROBE_READY_" + stage)
                    && process.isAlive() && System.nanoTime() < startupDeadline) Thread.sleep(50);
            assertTrue(java.nio.file.Files.readString(output).contains("CRASH_PROBE_READY_" + stage),
                    () -> "Child did not reach crash barrier. Inspect " + output);
            var lock = observer.getLock("lock:student:1000");
            assertTrue(lock.isLocked());
            assertTrue(lock.remainTimeToLive() > 0);
            assertCourse(1, beforeCommit ? 0 : 1);
            process.destroyForcibly();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            assertFalse(java.nio.file.Files.readString(output).contains("CRASH_PROBE_FINALLY_EXECUTED"));
            long expiryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (lock.isLocked() && System.nanoTime() < expiryDeadline) Thread.sleep(50);
            assertFalse(lock.isLocked(), "Watchdog must stop renewing after JVM termination");
            assertCourse(1, beforeCommit ? 0 : 1);
            if (beforeCommit) {
                assertNotNull(facade.enroll(1000L, 1L));
            } else {
                assertNotNull(service.findConfirmedEnrollment(1000L, 1L));
                assertEquals("DUPLICATE_ENROLLMENT", assertThrows(EnrollmentException.class,
                        () -> facade.enroll(1000L, 1L)).getCode());
            }
            assertCourse(1, 1);
        } finally {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
            observer.shutdown();
        }
    }

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
    void retryAfterCommittedResponseIsDiscardedDoesNotInsertTwice() throws Exception {
        // Discard the first successful response after the service transaction commits.
        // This models client uncertainty, not a real network disconnect.
        Long committedId = facade.enroll(1000L, 1L);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/enrollments/confirmation").param("studentId", "1000").param("courseId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrollmentId").value(committedId));
        mvc.perform(post("/api/enrollments").contentType("application/json")
                .content("{\"studentId\":1000,\"courseId\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_ENROLLMENT"));
        assertCourse(1, 1);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM enrollments WHERE student_id=1000", Integer.class));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void expiredStudentLeaseCannotCommitStaleValidation(boolean conflictingSchedule) throws Exception {
        // Force a short lease only in this test. Production uses watchdog renewal.
        // The interleaving is: A validates -> A loses lease -> B commits -> A resumes.
        jdbc.update("UPDATE students SET max_credits=? WHERE id=1000", conflictingSchedule ? 18 : 3);
        Long addedSchedule = null;
        if (conflictingSchedule) {
            jdbc.update("INSERT INTO course_schedules(course_id,day_of_week,start_time,end_time) "
                    + "VALUES(2,'MONDAY','09:00:00','10:30:00')");
            addedSchedule = jdbc.queryForObject("SELECT MAX(id) FROM course_schedules", Long.class);
        }
        var config = new Config(); config.useSingleServer().setAddress(address());
        var oldClient = Redisson.create(config);
        var oldLock = oldClient.getLock("lock:student:1000");
        try {
            assertTrue(oldLock.tryLock(1, 1, TimeUnit.SECONDS));
            service.validateForEnrollment(1000L, 1L);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (oldLock.isLocked() && System.nanoTime() < deadline) Thread.sleep(20);
            assertFalse(oldLock.isLocked(), "The injected lease must actually expire");
            assertNotNull(facade.enroll(1000L, 2L));
            // A normal fresh validation would now reject this application.
            var rejected = assertThrows(EnrollmentException.class,
                    () -> service.validateForEnrollment(1000L, 1L));
            assertEquals(conflictingSchedule ? "SCHEDULE_CONFLICT" : "CREDIT_LIMIT_EXCEEDED",
                    rejected.getCode());
            // But the suspended request resumes directly at the existing write method.
            var lateRequest = assertThrows(EnrollmentException.class,
                    () -> service.completeEnrollmentWithPessimisticLock(1000L, 1L));
            assertEquals(rejected.getCode(), lateRequest.getCode());
            assertCourse(1, 0);
            assertCourse(2, 1);
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM enrollments WHERE student_id=1000", Integer.class));
        } finally {
            oldClient.shutdown();
            if (addedSchedule != null) jdbc.update("DELETE FROM course_schedules WHERE id=?", addedSchedule);
        }
    }

    @Test
    void experimentReadActuallyQueriesDatabaseAndPoolReportsMode() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/experiment/read"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(1));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/experiment/pool"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.strategy").value("PESSIMISTIC"))
                .andExpect(jsonPath("$.gate").value(true)).andExpect(jsonPath("$.waiting").isNumber());
    }

    @Test
    void catalogFixturesAreRepeatableAndCursorPaginationIsCorrect() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                    new org.springframework.core.io.FileSystemResource("../jmeter/data/setup-load-test.sql"));
            for (int i=0; i<2; i++) {
                org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                        new org.springframework.core.io.FileSystemResource("../jmeter/data/setup-catalog-test.sql"));
            }
        }
        assertEquals(20000, jdbc.queryForObject("SELECT COUNT(*) FROM courses WHERE id BETWEEN 100000 AND 119999", Integer.class));
        assertEquals(10000, jdbc.queryForObject("SELECT COUNT(*) FROM students WHERE id BETWEEN 200000 AND 209999", Integer.class));
        assertEquals(20000, jdbc.queryForObject("SELECT COUNT(*) FROM enrollments WHERE course_id BETWEEN 100000 AND 119999", Integer.class));
        var get = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/courses");
        mvc.perform(get.param("department", "CS").param("afterId", "100000").param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(100005))
                .andExpect(jsonPath("$.items[1].id").value(100010))
                .andExpect(jsonPath("$.nextAfterId").value(100010));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/courses")
                .param("department", "CS").param("afterId", "100010").param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(100015));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/courses").param("size", "101"))
                .andExpect(status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/courses").param("department", "' OR 1=1 --"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
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
