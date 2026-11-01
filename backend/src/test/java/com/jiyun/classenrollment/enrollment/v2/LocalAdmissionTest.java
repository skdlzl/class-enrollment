package com.jiyun.classenrollment.enrollment.v2;

import com.jiyun.classenrollment.common.error.EnrollmentException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LocalAdmissionTest {
    private long deadline() { return System.nanoTime()+TimeUnit.SECONDS.toNanos(1); }
    @Test void timeoutDoesNotLeakPermitAndCloseIsIdempotent() {
        var gate=new LocalAdmission();
        var first=gate.enter(1,deadline());
        assertEquals("LOCAL_ADMISSION_TIMEOUT",assertThrows(EnrollmentException.class,
            ()->gate.enter(1,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(10))).getCode());
        first.close();first.close();
        try(var next=gate.enter(1,deadline())) {
            assertEquals("LOCAL_ADMISSION_TIMEOUT",assertThrows(EnrollmentException.class,
                ()->gate.enter(1,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(10))).getCode());
        }
    }
    @Test void interruptionIsPropagatedWithoutTakingPermit() {
        var gate=new LocalAdmission();
        Thread.currentThread().interrupt();
        try {
            assertEquals("INTERRUPTED",assertThrows(EnrollmentException.class,()->gate.enter(1,deadline())).getCode());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        try(var permit=gate.enter(1,deadline())) { assertNotNull(permit); }
    }
}
