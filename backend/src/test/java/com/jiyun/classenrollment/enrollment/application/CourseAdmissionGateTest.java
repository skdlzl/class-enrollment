package com.jiyun.classenrollment.enrollment.application;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CourseAdmissionGateTest {
    @Test
    void sameCourseSerializesAndOtherCourseProceeds() throws Exception {
        CourseAdmissionGate gate = new CourseAdmissionGate();
        try (var first = gate.register(1L); var same = gate.register(1L);
             var other = gate.register(2L)) {
            assertTrue(first.tryAcquire(1, TimeUnit.SECONDS));
            assertFalse(same.tryAcquire(1, TimeUnit.MILLISECONDS));
            assertTrue(other.tryAcquire(1, TimeUnit.SECONDS));
            first.close();
            assertTrue(same.tryAcquire(1, TimeUnit.SECONDS));
        }
        assertEquals(0, gate.size());
    }

    @Test
    void interruptedWaiterDoesNotLeakOrReleaseOwnersPermit() throws Exception {
        CourseAdmissionGate gate = new CourseAdmissionGate();
        try (var owner = gate.register(1L)) {
            assertTrue(owner.tryAcquire(1, TimeUnit.SECONDS));
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> result = executor.submit(() -> {
                    try (var waiter = gate.register(1L)) {
                        Thread.currentThread().interrupt();
                        assertThrows(InterruptedException.class,
                                () -> waiter.tryAcquire(1, TimeUnit.SECONDS));
                    } finally { Thread.interrupted(); }
                });
                result.get(2, TimeUnit.SECONDS);
                try (var next = gate.register(1L)) {
                    assertFalse(next.tryAcquire(1, TimeUnit.MILLISECONDS));
                }
                assertEquals(1, gate.size());
            } finally { executor.shutdownNow(); }
        }
        assertEquals(0, gate.size());
    }

    @Test
    void concurrentRegistrationAndEvictionNeverCreateTwoOwners() throws Exception {
        CourseAdmissionGate gate = new CourseAdmissionGate();
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(16);
        try {
            java.util.List<Future<?>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 16; i++) results.add(executor.submit(() -> {
                for (int j = 0; j < 100; j++) {
                    try (var ticket = gate.register(1L)) {
                        assertTrue(ticket.tryAcquire(5, TimeUnit.SECONDS));
                        assertEquals(1, inside.incrementAndGet());
                        Thread.yield();
                        assertEquals(0, inside.decrementAndGet());
                        completed.incrementAndGet();
                    } catch (InterruptedException e) { throw new RuntimeException(e); }
                }
            }));
            for (Future<?> result : results) result.get(10, TimeUnit.SECONDS);
            assertEquals(1600, completed.get());
            assertEquals(0, gate.size());
        } finally { executor.shutdownNow(); }
    }
}
