package com.jiyun.classenrollment.enrollment.v2;

import com.jiyun.classenrollment.common.error.EnrollmentException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class EnrollmentCoordinator {
    public enum Strategy { REDIS, PESSIMISTIC, OPTIMISTIC }
    public record Result(long enrollmentId, boolean replayed) {}
    private final JdbcTemplate db;
    private final RedisGate gate;
    private final EligibilityClient external;
    private final TransactionTemplate tx;
    private final Strategy strategy;
    private final MeterRegistry metrics;
    private final long budgetMs;
    private final LocalAdmission admission = new LocalAdmission();
    public EnrollmentCoordinator(JdbcTemplate db, RedisGate gate, EligibilityClient external,
            PlatformTransactionManager manager, MeterRegistry metrics,
            @Value("${enrollment.strategy:REDIS}") Strategy strategy,
            @Value("${enrollment.budget-ms:5000}") long budgetMs) {
        this.db = db; this.gate = gate; this.external = external; this.strategy = strategy;
        this.metrics = metrics; this.budgetMs = budgetMs;
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.setTimeout(2);
    }
    public Result enroll(long student, long course, String key) {
        if (student <= 0 || course <= 0 || key == null || !key.matches("[A-Za-z0-9._:-]{1,80}"))
            throw new EnrollmentException("INVALID_REQUEST", "양수 ID와 1~80자 Idempotency-Key가 필요합니다.", HttpStatus.BAD_REQUEST);
        long start = System.nanoTime(), deadline = start + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        String outcome = "UNEXPECTED";
        try {
            Result replay = replay(student, course, key);
            if (replay != null) { outcome = "REPLAY"; return replay; }
            // A committed full state is enough to reject; all strategies get the same fast path.
            Result closedReplay = replayIfFull(student, course, key);
            if (closedReplay != null) { outcome = "REPLAY"; return closedReplay; }
            for (int attempt = 0; attempt < 8; attempt++) {
                checkDeadline(deadline);
                try {
                    Result result;
                    if (strategy == Strategy.REDIS) {
                        long localStart=System.nanoTime();
                        LocalAdmission.Permit entered;
                        try { entered=admission.enter(course,deadline); }
                        finally { record("local_wait",localStart); }
                        try (var permit=entered) {
                            result=replayIfFull(student,course,key);
                            if (result==null) {
                                long wait = System.nanoTime();
                                RedisGate.Lease acquired;
                                try { acquired = gate.acquire(course, deadline); }
                                finally { record("lock_wait", wait); }
                                try (var lease = acquired) {
                                    result = replay(student, course, key);
                                    if (result == null) {
                                        result=replayIfFull(student,course,key);
                                        if (result==null) {
                                            callExternal(student, course, deadline);
                                            lease.check();
                                            result = transact(student, course, key, deadline, lease);
                                        }
                                    }
                                }
                            }
                        }
                    } else result = transact(student, course, key, deadline, null);
                    outcome = result.replayed() ? "REPLAY" : "CREATED";
                    return result;
                } catch (Retry | TransientDataAccessException e) {
                    metrics.counter("enrollment.retries", "strategy", strategy.name(), "cause", retryCause(e)).increment();
                    try { Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextLong(2, 11)); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw RedisGate.failure("INTERRUPTED"); }
                } catch (DuplicateKeyException e) {
                    Result replayed = replay(student, course, key);
                    if (replayed != null) { outcome = "REPLAY"; return replayed; }
                    throw conflict("DUPLICATE_ENROLLMENT");
                }
            }
            throw RedisGate.failure("RETRY_EXHAUSTED");
        } catch (EnrollmentException e) {
            outcome = e.getCode();
            if (e.getStatus().is5xxServerError())
                org.slf4j.LoggerFactory.getLogger(EnrollmentCoordinator.class).warn(
                    "event=enrollment_failed strategy={} code={} elapsed_ms={}", strategy, outcome,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));
            throw e;
        }
        catch (org.springframework.jdbc.CannotGetJdbcConnectionException | org.springframework.transaction.CannotCreateTransactionException e) {
            outcome = "DB_CONNECTION_UNAVAILABLE"; throw RedisGate.failure(outcome);
        } catch (org.springframework.transaction.TransactionException e) {
            outcome = "DB_TRANSACTION_ERROR"; throw RedisGate.failure(outcome);
        } catch (DataAccessException e) {
            outcome = "DB_UNAVAILABLE"; throw RedisGate.failure(outcome);
        } finally {
            metrics.timer("enrollment.requests", "strategy", strategy.name(), "outcome", outcome)
                .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }
    private Result transact(long student, long course, String key, long deadline, RedisGate.Lease lease) {
        return transact(student, course, key, deadline, lease, null, null);
    }
    public Result commitExchange(long student, long course, String key, long deadline, RedisGate.Lease lease, String requestId, String owner) {
        return transact(student, course, key, deadline, lease, requestId, owner);
    }
    private Result transact(long student, long course, String key, long deadline, RedisGate.Lease lease, String requestId, String owner) {
        long start = System.nanoTime();
        try {
            return tx.execute(status -> {
                if (requestId != null) {
                    var held = db.queryForList("SELECT id FROM exchange_requests WHERE id=? AND owner=? AND claim_until>NOW(6) AND phase='RESERVING' FOR UPDATE", requestId, owner);
                    if (held.isEmpty()) throw RedisGate.failure("EXCHANGE_CLAIM_LOST");
                }
                checkDeadline(deadline);
                // A successful idempotency record is committed atomically with the enrollment.
                db.update("INSERT INTO enrollment_requests(student_id,request_key,course_id) VALUES (?,?,?)", student, key, course);
                var courses = db.queryForList("SELECT * FROM courses WHERE id=?" +
                    (strategy == Strategy.PESSIMISTIC ? " FOR UPDATE" : ""), course);
                if (courses.isEmpty()) throw missing("COURSE_NOT_FOUND");
                var c = courses.get(0);
                var students = db.queryForList("SELECT * FROM students WHERE id=?", student);
                if (students.isEmpty()) throw missing("STUDENT_NOT_FOUND");
                var s = students.get(0);
                if (!"ACTIVE".equals(s.get("status"))) throw conflict("STUDENT_NOT_ACTIVE");
                if (count("SELECT COUNT(*) FROM enrollments WHERE student_id=? AND course_id=?", student, course) > 0)
                    throw conflict("DUPLICATE_ENROLLMENT");
                if (number(c,"enrolled_count") >= number(c,"capacity")) throw conflict("COURSE_FULL");
                long credits = count("SELECT COALESCE(SUM(c.credits),0) FROM enrollments e JOIN courses c ON c.id=e.course_id WHERE e.student_id=? AND c.academic_year=? AND c.semester=?", student, c.get("academic_year"), c.get("semester"));
                if (credits + number(c,"credits") > number(s,"max_credits")) throw conflict("CREDIT_LIMIT_EXCEEDED");
                if (count("""
                    SELECT COUNT(*) FROM enrollments e JOIN courses old ON old.id=e.course_id
                    JOIN course_schedules a ON a.course_id=e.course_id
                    JOIN course_schedules b ON b.course_id=? AND a.day_of_week=b.day_of_week
                    AND a.start_time < b.end_time AND b.start_time < a.end_time
                    WHERE e.student_id=? AND old.academic_year=? AND old.semester=?
                    """, course, student, c.get("academic_year"), c.get("semester")) > 0) throw conflict("SCHEDULE_CONFLICT");
                if (requestId == null && strategy != Strategy.REDIS) callExternal(student, course, deadline);
                checkDeadline(deadline);
                // All writers use course -> student -> enrollment ordering. CAS is the final safety boundary.
                if (db.update("UPDATE courses SET enrolled_count=enrolled_count+1,version=version+1 WHERE id=? AND version=? AND enrolled_count<capacity", course, c.get("version")) != 1) throw new Retry();
                if (db.update("UPDATE students SET version=version+1 WHERE id=? AND version=?", student, s.get("version")) != 1) throw new Retry();
                db.update("INSERT INTO enrollments(student_id,course_id) VALUES (?,?)", student, course);
                long id = count("SELECT id FROM enrollments WHERE student_id=? AND course_id=?", student, course);
                db.update("UPDATE enrollment_requests SET enrollment_id=? WHERE student_id=? AND request_key=?", id, student, key);
                checkDeadline(deadline);
                if (lease != null) lease.check();
                if (requestId != null) db.update("UPDATE exchange_requests SET phase='CONFIRMING',enrollment_id=?,last_error=NULL WHERE id=? AND owner=?", id, requestId, owner);
                return new Result(id, false);
            });
        } finally { record("transaction", start); }
    }
    private Result replayIfFull(long student, long course, String key) {
        if (count("SELECT COUNT(*) FROM courses WHERE id=? AND enrolled_count>=capacity", course)==0) return null;
        // The winner may have committed between the initial replay lookup and this full check.
        Result existing=replay(student,course,key);
        if (existing!=null) return existing;
        throw conflict("COURSE_FULL");
    }
    private Result replay(long student, long course, String key) {
        var rows = db.queryForList("SELECT course_id,enrollment_id FROM enrollment_requests WHERE student_id=? AND request_key=?", student, key);
        if (rows.isEmpty()) return null;
        var row = rows.get(0);
        if (number(row,"course_id") != course) throw conflict("IDEMPOTENCY_KEY_REUSED");
        if (row.get("enrollment_id") == null) throw RedisGate.failure("REQUEST_IN_PROGRESS");
        return new Result(number(row,"enrollment_id"), true);
    }
    private void callExternal(long student, long course, long deadline) {
        long start = System.nanoTime();
        try { external.verify(student, course, deadline); }
        finally { record("external", start); }
    }
    private void record(String stage, long start) {
        metrics.timer("enrollment.stage", "strategy", strategy.name(), "stage", stage)
            .record(System.nanoTime()-start, TimeUnit.NANOSECONDS);
    }
    private void checkDeadline(long deadline) { if (System.nanoTime() >= deadline) throw RedisGate.failure("REQUEST_DEADLINE"); }
    private long count(String sql, Object... args) { return Objects.requireNonNull(db.queryForObject(sql, Long.class, args)); }
    private static long number(Map<String,Object> row, String key) { return ((Number)row.get(key)).longValue(); }
    private static EnrollmentException conflict(String code) { return new EnrollmentException(code, code, HttpStatus.CONFLICT); }
    private static EnrollmentException missing(String code) { return new EnrollmentException(code, code, HttpStatus.NOT_FOUND); }
    private static String retryCause(RuntimeException error) {
        if (error instanceof Retry) return "VERSION_CONFLICT";
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql) {
                if (sql.getErrorCode() == 1213) return "DB_DEADLOCK";
                if (sql.getErrorCode() == 1205) return "DB_LOCK_WAIT_TIMEOUT";
            }
        }
        return error.getClass().getSimpleName();
    }
    private static final class Retry extends RuntimeException {}
}
