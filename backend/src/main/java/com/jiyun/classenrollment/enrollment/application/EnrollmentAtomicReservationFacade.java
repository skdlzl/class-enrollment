package com.jiyun.classenrollment.enrollment.application;

import com.jiyun.classenrollment.common.error.EnrollmentException;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class EnrollmentAtomicReservationFacade {

    private static final long STUDENT_LOCK_WAIT_SECONDS = 10L;

    private final EnrollmentService enrollmentService;
    private final CourseSeatReservation courseSeatReservation;
    private final RedissonClient redissonClient;

    public EnrollmentAtomicReservationFacade(
            EnrollmentService enrollmentService,
            CourseSeatReservation courseSeatReservation,
            RedissonClient redissonClient
    ) {
        this.enrollmentService = enrollmentService;
        this.courseSeatReservation = courseSeatReservation;
        this.redissonClient = redissonClient;
    }

    public Long enroll(Long studentId, Long courseId) {
        String permitId = courseSeatReservation.reserve(courseId);
        RLock studentLock =
                redissonClient.getLock("lock:student:" + studentId);

        boolean studentLockAcquired = false;
        boolean enrollmentCompleted = false;

        try {
            studentLockAcquired = studentLock.tryLock(
                    STUDENT_LOCK_WAIT_SECONDS,
                    TimeUnit.SECONDS
            );

            if (!studentLockAcquired) {
                throw new EnrollmentException(
                        "LOCK_ACQUISITION_TIMEOUT",
                        "학생의 다른 수강신청 요청이 처리 중입니다. 잠시 후 다시 시도해 주세요.",
                        HttpStatus.SERVICE_UNAVAILABLE
                );
            }

            Long enrollmentId =
                    enrollmentService.enrollWithReservedSeat(studentId, courseId);
            enrollmentCompleted = true;

            return enrollmentId;

        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "학생 락 대기 중 요청이 중단되었습니다.",
                    exception
            );

        } finally {
            if (studentLockAcquired && studentLock.isHeldByCurrentThread()) {
                studentLock.unlock();
            }

            if (!enrollmentCompleted) {
                courseSeatReservation.release(courseId, permitId);
            }
        }
    }
}
