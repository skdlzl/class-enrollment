package com.jiyun.classenrollment.enrollment.application;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class LocalCourseGateTest {
    @Test void serializesSameCourseAndReclaimsEntries() throws Exception {
        var gate = new LocalCourseGate();
        var pool = Executors.newFixedThreadPool(8);
        var active = new AtomicInteger();
        var peak = new AtomicInteger();
        try {
            var jobs = new java.util.ArrayList<Future<?>>();
            for (int i=0; i<80; i++) jobs.add(pool.submit(() -> {
                try (var permit = gate.acquire(1, 5000)) {
                    assertNotNull(permit);
                    peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                    Thread.yield();
                    active.decrementAndGet();
                } catch (InterruptedException e) { throw new RuntimeException(e); }
            }));
            for (var job : jobs) job.get(10, TimeUnit.SECONDS);
            assertEquals(1, peak.get());
            assertEquals(0, gate.trackedCourses());
        } finally { pool.shutdownNow(); }
    }
    @Test void timeoutAndExceptionDoNotLeakPermit() throws Exception {
        var gate = new LocalCourseGate();
        try (var held = gate.acquire(1, 1000)) {
            assertNull(gate.acquire(1, 1));
            try (var other = gate.acquire(2, 1000)) { assertNotNull(other); }
            assertEquals(1, gate.trackedCourses());
        }
        assertThrows(IllegalStateException.class, () -> {
            try (var held = gate.acquire(1, 1000)) { throw new IllegalStateException("injected"); }
        });
        try (var next = gate.acquire(1, 1000)) { assertNotNull(next); }
        assertEquals(0, gate.trackedCourses());
    }
    @Test void interruptedWaiterIsRemoved() throws Exception {
        var gate = new LocalCourseGate();
        try (var held = gate.acquire(1, 1000)) {
            var result = new CompletableFuture<Boolean>();
            var thread = new Thread(() -> {
                Thread.currentThread().interrupt();
                try (var permit = gate.acquire(1, 5000)) { result.complete(false); }
                catch (InterruptedException expected) { result.complete(true); }
            });
            thread.start();
            assertTrue(result.get(5, TimeUnit.SECONDS));
        }
        assertEquals(0, gate.trackedCourses());
    }
}
