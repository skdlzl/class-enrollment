package com.jiyun.classenrollment.enrollment.application;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnrollmentLockScopeExperimentTest {

    @Test
    void 과목락만_사용하면_같은학생의_서로다른_과목신청은_동시에_실행된다()
            throws Exception {
        LockScopeExecutor courseLockOnly = new LockScopeExecutor(false, true);

        boolean overlapped = didOverlap(
                courseLockOnly,
                new EnrollmentRequest(1L, 10L),
                new EnrollmentRequest(1L, 20L)
        );

        assertTrue(
                overlapped,
                "과목 키가 다르므로 동일 학생의 두 요청이 겹쳐 최대 학점 검증 경쟁이 발생할 수 있습니다."
        );
    }

    @Test
    void 학생락만_사용하면_서로다른학생의_같은과목신청은_동시에_실행된다()
            throws Exception {
        LockScopeExecutor studentLockOnly = new LockScopeExecutor(true, false);

        boolean overlapped = didOverlap(
                studentLockOnly,
                new EnrollmentRequest(1L, 10L),
                new EnrollmentRequest(2L, 10L)
        );

        assertTrue(
                overlapped,
                "학생 키가 다르므로 같은 과목의 좌석 변경이 겹쳐 정원 경쟁이 발생할 수 있습니다."
        );
    }

    @Test
    void 학생락과_과목락을_함께_사용하면_두_종류의_경쟁을_모두_직렬화한다()
            throws Exception {
        LockScopeExecutor bothLocks = new LockScopeExecutor(true, true);

        boolean sameStudentDifferentCoursesOverlapped = didOverlap(
                bothLocks,
                new EnrollmentRequest(1L, 10L),
                new EnrollmentRequest(1L, 20L)
        );

        boolean differentStudentsSameCourseOverlapped = didOverlap(
                bothLocks,
                new EnrollmentRequest(1L, 10L),
                new EnrollmentRequest(2L, 10L)
        );

        assertFalse(
                sameStudentDifferentCoursesOverlapped,
                "같은 학생의 요청은 학생 락으로 한 번에 하나씩 실행되어야 합니다."
        );
        assertFalse(
                differentStudentsSameCourseOverlapped,
                "같은 과목의 요청은 과목 락으로 한 번에 하나씩 실행되어야 합니다."
        );
    }

    private boolean didOverlap(
            LockScopeExecutor lockScopeExecutor,
            EnrollmentRequest firstRequest,
            EnrollmentRequest secondRequest
    ) throws Exception {
        ExecutorService executorService = Executors.newFixedThreadPool(2);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);

        try {
            Future<?> first = executorService.submit(() ->
                    lockScopeExecutor.execute(firstRequest, () -> {
                        firstEntered.countDown();
                        await(releaseFirst);
                    })
            );

            assertTrue(
                    firstEntered.await(1, TimeUnit.SECONDS),
                    "첫 번째 요청이 임계영역에 진입해야 합니다."
            );

            Future<?> second = executorService.submit(() ->
                    lockScopeExecutor.execute(secondRequest, secondEntered::countDown)
            );

            boolean overlapped = secondEntered.await(300, TimeUnit.MILLISECONDS);
            releaseFirst.countDown();

            first.get(1, TimeUnit.SECONDS);
            second.get(1, TimeUnit.SECONDS);

            return overlapped;

        } finally {
            releaseFirst.countDown();
            executorService.shutdownNow();
        }
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private record EnrollmentRequest(Long studentId, Long courseId) {
    }

    private static class LockScopeExecutor {

        private final boolean useStudentLock;
        private final boolean useCourseLock;
        private final Map<Long, ReentrantLock> studentLocks = new ConcurrentHashMap<>();
        private final Map<Long, ReentrantLock> courseLocks = new ConcurrentHashMap<>();

        private LockScopeExecutor(boolean useStudentLock, boolean useCourseLock) {
            this.useStudentLock = useStudentLock;
            this.useCourseLock = useCourseLock;
        }

        private void execute(EnrollmentRequest request, Runnable criticalSection) {
            ReentrantLock studentLock = useStudentLock
                    ? studentLocks.computeIfAbsent(request.studentId(), key -> new ReentrantLock())
                    : null;
            ReentrantLock courseLock = useCourseLock
                    ? courseLocks.computeIfAbsent(request.courseId(), key -> new ReentrantLock())
                    : null;

            lock(studentLock);

            try {
                lock(courseLock);

                try {
                    criticalSection.run();
                } finally {
                    unlock(courseLock);
                }
            } finally {
                unlock(studentLock);
            }
        }

        private void lock(ReentrantLock lock) {
            if (lock != null) {
                lock.lock();
            }
        }

        private void unlock(ReentrantLock lock) {
            if (lock != null) {
                lock.unlock();
            }
        }
    }
}
