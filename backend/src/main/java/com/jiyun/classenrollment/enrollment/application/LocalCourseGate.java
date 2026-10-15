package com.jiyun.classenrollment.enrollment.application;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** One permit per course per JVM, including references for waiting callers. */
public class LocalCourseGate {
    private final ConcurrentHashMap<Long, Entry> entries = new ConcurrentHashMap<>();
    private static class Entry {
        final Semaphore semaphore = new Semaphore(1, true);
        int references;
    }
    public Permit acquire(long courseId, long timeoutMillis) throws InterruptedException {
        Entry entry = entries.compute(courseId, (id, current) -> {
            if (current == null) current = new Entry();
            current.references++;
            return current;
        });
        boolean acquired = false;
        try {
            acquired = entry.semaphore.tryAcquire(timeoutMillis, TimeUnit.MILLISECONDS);
            return acquired ? new Permit(courseId, entry) : null;
        } finally {
            if (!acquired) dereference(courseId, entry);
        }
    }
    private void dereference(long id, Entry entry) {
        entries.compute(id, (key, current) -> {
            if (current != entry) throw new IllegalStateException("gate identity changed");
            return --entry.references == 0 ? null : entry;
        });
    }
    int trackedCourses() { return entries.size(); }
    public final class Permit implements AutoCloseable {
        private final long id;
        private final Entry entry;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Permit(long id, Entry entry) { this.id = id; this.entry = entry; }
        @Override public void close() {
            if (closed.compareAndSet(false, true)) {
                entry.semaphore.release();
                dereference(id, entry);
            }
        }
    }
}
