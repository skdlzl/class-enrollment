package com.jiyun.classenrollment.enrollment.application;

import com.jiyun.classenrollment.common.error.EnrollmentException;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;

/** Runs ONLY against the disposable MySQL database configured by the dedicated CI job. */
@SpringBootTest
@Import(EnrollmentDeadlockAnalysisTest.Diagnostics.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EnrollmentDeadlockAnalysisTest {
    static final Path EVIDENCE = Path.of("target/deadlock-evidence");
    static final Queue<String> SQL = new ConcurrentLinkedQueue<>();
    static volatile CountDownLatch arrivals;
    static volatile CountDownLatch release;
    static volatile JdbcTemplate diagnosticJdbc;
    @Autowired EnrollmentService service;
    @Autowired JdbcTemplate jdbc;

    @TestConfiguration
    static class Diagnostics {
        @Bean HibernatePropertiesCustomizer sqlInspector() {
            return properties -> properties.put("hibernate.session_factory.statement_inspector",
                    (StatementInspector) sql -> {
                        if (!Thread.currentThread().getName().startsWith("deadlock-worker-")) return sql;
                        SQL.add(Thread.currentThread().getName() + " | " + sql);
                        CountDownLatch barrier = arrivals;
                        if (barrier != null && sql.toLowerCase(Locale.ROOT).matches("(?s).*\\bupdate\\s+courses\\b.*")) {
                            barrier.countDown();
                            if (barrier.getCount() == 0) {
                                try {
                                    write("before-update-locks.txt", diagnosticJdbc.queryForList("""
                                        SELECT ENGINE_TRANSACTION_ID, THREAD_ID, OBJECT_NAME, INDEX_NAME,
                                               LOCK_TYPE, LOCK_MODE, LOCK_STATUS, LOCK_DATA
                                        FROM performance_schema.data_locks
                                        WHERE OBJECT_SCHEMA = DATABASE() AND OBJECT_NAME = 'courses'
                                        ORDER BY ENGINE_TRANSACTION_ID, LOCK_TYPE
                                        """).toString());
                                } finally { release.countDown(); }
                            }
                            try {
                                if (!release.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("UPDATE barrier timed out");
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(e);
                            }
                        }
                        return sql;
                    });
        }
    }

    @BeforeEach
    void prepare() throws Exception {
        Files.createDirectories(EVIDENCE);
        // The workflow creates this named database in a new MySQL service container.
        assertEquals("deadlock_analysis", jdbc.queryForObject("SELECT DATABASE()", String.class));
        diagnosticJdbc = jdbc;
        arrivals = null;
        SQL.clear();
        jdbc.update("DELETE FROM enrollments WHERE course_id = 2 OR student_id BETWEEN 100000 AND 100004");
        jdbc.update("DELETE FROM students WHERE id BETWEEN 100000 AND 100004");
        jdbc.update("UPDATE courses SET capacity = 2, enrolled_count = 0 WHERE id = 2");
        for (long id = 100000; id < 100005; id++) {
            jdbc.update("INSERT INTO students(id,student_number,name,status,max_credits) VALUES(?,?,?,'ACTIVE',18)",
                    id, "DL" + id, "Deadlock test " + id);
        }
        write("environment.txt", jdbc.queryForList("SELECT VERSION() AS version, @@transaction_isolation AS isolation_level, @@innodb_autoinc_lock_mode AS autoinc_mode").toString());
    }

    @Test @Order(1)
    void originalServiceShowsForeignKeySharedLockUpgradeDeadlock() throws Exception {
        // A diagnostic pause immediately before the generated UPDATE ensures all five
        // IDENTITY INSERTs have finished. Production SQL and transaction boundaries stay unchanged.
        arrivals = new CountDownLatch(5);
        release = new CountDownLatch(1);
        try {
            List<String> outcomes = runFive(false);
            write("original-outcomes.txt", outcomes.toString());
            write("original-sql.txt", String.join("\n", SQL));
            write("innodb-status.txt", jdbc.queryForList("SHOW ENGINE INNODB STATUS").toString());
            write("original-final-state.txt", finalState());
            assertEquals(1, Collections.frequency(outcomes, "SUCCESS"));
            assertEquals(4, Collections.frequency(outcomes, "MYSQL_1213_40001"));
            String locks = Files.readString(EVIDENCE.resolve("before-update-locks.txt"));
            assertTrue(locks.contains("LOCK_MODE=S,REC_NOT_GAP"), locks);
            String status = Files.readString(EVIDENCE.resolve("innodb-status.txt"));
            assertTrue(status.contains("LATEST DETECTED DEADLOCK"), status);
            assertTrue(status.contains("lock_mode X locks rec but not gap waiting"), status);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM enrollments WHERE course_id=2", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT enrolled_count FROM courses WHERE id=2", Integer.class));
        } finally { release.countDown(); arrivals = null; }
    }

    @Test @Order(2)
    void serializingTheWholeTransactionRestoresTwoSuccessesAndThreeFullRejections() throws Exception {
        List<String> outcomes = runFive(true);
        write("serialized-outcomes.txt", outcomes.toString());
        write("serialized-sql.txt", String.join("\n", SQL));
        write("serialized-final-state.txt", finalState());
        assertEquals(2, Collections.frequency(outcomes, "SUCCESS"));
        assertEquals(3, Collections.frequency(outcomes, "COURSE_FULL"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM enrollments WHERE course_id=2", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT enrolled_count FROM courses WHERE id=2", Integer.class));
    }

    private List<String> runFive(boolean serialized) throws Exception {
        AtomicInteger threadNumber = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(5,
                runnable -> new Thread(runnable, "deadlock-worker-" + threadNumber.incrementAndGet()));
        CountDownLatch ready = new CountDownLatch(5), start = new CountDownLatch(1);
        ReentrantLock courseLock = new ReentrantLock();
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (long id = 100000; id < 100005; id++) {
                long student = id;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    if (serialized) courseLock.lock();
                    try {
                        service.enroll(student, 2L); // Spring proxy returns after commit.
                        return "SUCCESS";
                    } catch (EnrollmentException e) {
                        return e.getCode();
                    } catch (Exception e) {
                        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                            if (cause instanceof SQLException sql && sql.getErrorCode() == 1213
                                    && "40001".equals(sql.getSQLState())) return "MYSQL_1213_40001";
                        }
                        return "UNEXPECTED:" + e;
                    } finally { if (serialized) courseLock.unlock(); }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> future : futures) outcomes.add(future.get(45, TimeUnit.SECONDS));
            return outcomes;
        } finally {
            start.countDown();
            if (release != null) release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private String finalState() {
        return jdbc.queryForList("SELECT capacity,enrolled_count,(SELECT COUNT(*) FROM enrollments WHERE course_id=2) AS enrollment_count FROM courses WHERE id=2").toString();
    }

    private static void write(String name, String text) {
        try { Files.writeString(EVIDENCE.resolve(name), text); }
        catch (Exception e) { throw new IllegalStateException("Cannot save evidence " + name, e); }
    }
}
