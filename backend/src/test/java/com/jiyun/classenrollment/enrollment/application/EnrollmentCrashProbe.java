package com.jiyun.classenrollment.enrollment.application;

import com.jiyun.classenrollment.ClassEnrollmentApplication;
import jakarta.persistence.EntityManagerFactory;
import org.redisson.api.RedissonClient;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Test-only child JVM. No fault-injection endpoints are added to the application. */
public final class EnrollmentCrashProbe {
    public static void main(String[] args) throws Exception {
        boolean beforeCommit = args[0].equals("BEFORE_COMMIT");
        try (var context = new SpringApplicationBuilder(ClassEnrollmentApplication.class)
                .web(WebApplicationType.NONE)
                .run("--experiment.enabled=false", "--enrollment.queue.enabled=false",
                        "--redisson.lock-watchdog-timeout-ms=3000", "--spring.main.banner-mode=off",
                        "--logging.level.root=WARN")) {
            var redis = context.getBean(RedissonClient.class);
            var lock = redis.getLock("lock:student:1000");
            lock.lock(); // Watchdog-managed lease, as in the real facade.
            try {
                var service = context.getBean(EnrollmentService.class);
                service.validateForEnrollment(1000L, 1L);
                var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
                tx.executeWithoutResult(status -> {
                    service.completeEnrollmentWithPessimisticLock(1000L, 1L);
                    java.util.Objects.requireNonNull(
                            org.springframework.orm.jpa.EntityManagerFactoryUtils.getTransactionalEntityManager(
                                    context.getBean(EntityManagerFactory.class))).flush();
                    if (beforeCommit) awaitKill("BEFORE_COMMIT");
                });
                // This marker is printed only AFTER commit; no enrollment response is returned.
                awaitKill("AFTER_COMMIT");
            } finally {
                System.out.println("CRASH_PROBE_FINALLY_EXECUTED");
                if (lock.isHeldByCurrentThread()) lock.unlock();
            }
        }
    }

    private static void awaitKill(String stage) {
        System.out.println("CRASH_PROBE_READY_" + stage);
        System.out.flush();
        try {
            // Parent keeps stdin open and kills the process, never releasing this barrier.
            if (System.in.read() == -1) throw new IllegalStateException("Parent stdin closed unexpectedly");
            throw new IllegalStateException("Crash barrier must only be released by process termination");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
