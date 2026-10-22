package com.jiyun.classenrollment.enrollment.application;

import com.jiyun.classenrollment.common.error.EnrollmentException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Per JVM synchronous-write admission, outside the transactional service proxy. */
@Component
public class EnrollmentDbAdmission {
    private final int limit, maxWaiting;
    private final long waitMs;
    private final Semaphore permits;
    private final AtomicInteger waiting = new AtomicInteger(), active = new AtomicInteger();
    private final AtomicLong admitted = new AtomicLong(), timedOut = new AtomicLong(), rejected = new AtomicLong(),
            interrupted = new AtomicLong(), waitNanos = new AtomicLong(), maxWaitNanos = new AtomicLong();

    public EnrollmentDbAdmission(@Value("${enrollment.db-admission.limit:0}") int limit,
            @Value("${enrollment.db-admission.wait-ms:3000}") long waitMs,
            @Value("${enrollment.db-admission.max-waiting:500}") int maxWaiting) {
        if (limit < 0 || waitMs < 0 || maxWaiting < 1) throw new IllegalArgumentException("Invalid DB admission settings");
        this.limit = limit; this.waitMs = waitMs; this.maxWaiting = maxWaiting;
        permits = new Semaphore(limit, true);
    }

    public <T> T execute(Supplier<T> transaction) {
        if (limit == 0) return transaction.get();
        if (waiting.incrementAndGet() > maxWaiting) {
            waiting.decrementAndGet(); rejected.incrementAndGet();
            throw unavailable("DB_ADMISSION_FULL", "신청 처리 대기 인원이 많습니다. 잠시 후 다시 시도해 주세요.");
        }
        long started = System.nanoTime();
        boolean acquired;
        try {
            acquired = permits.tryAcquire(waitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            interrupted.incrementAndGet(); Thread.currentThread().interrupt();
            throw unavailable("DB_ADMISSION_INTERRUPTED", "신청 처리 대기가 중단됐습니다.");
        } finally {
            waiting.decrementAndGet();
            long elapsed = System.nanoTime() - started;
            waitNanos.addAndGet(elapsed); maxWaitNanos.accumulateAndGet(elapsed, Math::max);
        }
        if (!acquired) {
            timedOut.incrementAndGet();
            throw unavailable("DB_ADMISSION_TIMEOUT", "신청 처리 대기 시간이 초과됐습니다.");
        }
        admitted.incrementAndGet(); active.incrementAndGet();
        try {
            // Spring proxy returns only after commit/rollback; release after that.
            return transaction.get();
        } finally {
            active.decrementAndGet(); permits.release();
        }
    }

    public Map<String, Object> snapshot() {
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("limit", limit); result.put("waitMs", waitMs); result.put("maxWaiting", maxWaiting);
        result.put("active", active.get()); result.put("waiting", waiting.get());
        result.put("admitted", admitted.get()); result.put("timeouts", timedOut.get());
        result.put("rejected", rejected.get()); result.put("interrupted", interrupted.get());
        result.put("waitTotalMs", waitNanos.get() / 1_000_000.0);
        result.put("waitMaxMs", maxWaitNanos.get() / 1_000_000.0);
        return result;
    }
    private EnrollmentException unavailable(String code, String message) {
        return new EnrollmentException(code, message, HttpStatus.SERVICE_UNAVAILABLE);
    }
}
