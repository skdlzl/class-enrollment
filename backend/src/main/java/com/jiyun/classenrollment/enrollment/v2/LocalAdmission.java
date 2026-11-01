package com.jiyun.classenrollment.enrollment.v2;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Bounded-memory, per-JVM admission; Redis still arbitrates between JVMs. */
public final class LocalAdmission {
    private final Semaphore[] stripes = new Semaphore[1024];
    public LocalAdmission() {
        for (int i=0; i<stripes.length; i++) stripes[i]=new Semaphore(1,true);
    }
    public Permit enter(long course, long deadline) {
        Semaphore semaphore = stripes[Math.floorMod(course,stripes.length)];
        long remaining=deadline-System.nanoTime();
        try {
            if (remaining<=0 || !semaphore.tryAcquire(remaining,TimeUnit.NANOSECONDS))
                throw RedisGate.failure("LOCAL_ADMISSION_TIMEOUT");
            return new Permit(semaphore);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw RedisGate.failure("INTERRUPTED");
        }
    }
    public static final class Permit implements AutoCloseable {
        private final Semaphore semaphore;
        private boolean closed;
        Permit(Semaphore semaphore) { this.semaphore=semaphore; }
        public void close() {
            if (!closed) { closed=true; semaphore.release(); }
        }
    }
}
