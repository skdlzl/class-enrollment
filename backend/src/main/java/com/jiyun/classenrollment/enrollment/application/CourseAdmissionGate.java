package com.jiyun.classenrollment.enrollment.application;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** JVM-local admission only. It must never replace the distributed course lock. */
final class CourseAdmissionGate {
    private final ConcurrentHashMap<Long, Entry> entries = new ConcurrentHashMap<>();

    Ticket register(Long courseId) {
        Entry entry = entries.compute(courseId, (id, current) -> {
            Entry result = current == null ? new Entry() : current;
            result.references++;
            return result;
        });
        return new Ticket(courseId, entry);
    }

    int size() {
        return entries.size();
    }

    private static final class Entry {
        final Semaphore permit = new Semaphore(1, true);
        int references; // Accessed only within compute for this course ID.
    }

    final class Ticket implements AutoCloseable {
        private final Long courseId;
        private final Entry entry;
        private boolean acquired;
        private boolean closed;

        private Ticket(Long courseId, Entry entry) {
            this.courseId = courseId;
            this.entry = entry;
        }

        boolean tryAcquire(long timeout, TimeUnit unit) throws InterruptedException {
            if (closed || acquired) {
                throw new IllegalStateException("Ticket already used");
            }
            if (timeout <= 0L) {
                return false;
            }
            acquired = entry.permit.tryAcquire(timeout, unit);
            return acquired;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (acquired) {
                entry.permit.release();
            }
            entries.compute(courseId, (id, current) -> {
                if (current != entry) {
                    throw new IllegalStateException("Course gate identity changed");
                }
                return --current.references == 0 ? null : current;
            });
        }
    }
}
