package com.jiyun.classenrollment.enrollment.application;

import com.jiyun.classenrollment.common.error.EnrollmentException;
import com.jiyun.classenrollment.course.domain.Course;
import com.jiyun.classenrollment.course.domain.CourseRepository;
import org.redisson.api.RLock;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RedissonClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class CourseSeatReservation {

    private static final String SEAT_KEY_PREFIX = "enrollment:course:";
    private static final String SEAT_KEY_SUFFIX = ":seats";

    private final CourseRepository courseRepository;
    private final RedissonClient redissonClient;

    public CourseSeatReservation(
            CourseRepository courseRepository,
            RedissonClient redissonClient
    ) {
        this.courseRepository = courseRepository;
        this.redissonClient = redissonClient;
    }

    public String reserve(Long courseId) {
        String seatKey = seatKey(courseId);
        RPermitExpirableSemaphore semaphore =
                redissonClient.getPermitExpirableSemaphore(seatKey);

        initializeIfNecessary(courseId, seatKey, semaphore);

        String permitId = semaphore.tryAcquire();

        if (permitId == null) {
            throw new EnrollmentException(
                    "COURSE_FULL",
                    "수강 정원이 마감되었습니다.",
                    HttpStatus.CONFLICT
            );
        }

        return permitId;
    }

    public void release(Long courseId, String permitId) {
        redissonClient
                .getPermitExpirableSemaphore(seatKey(courseId))
                .release(permitId);
    }

    private void initializeIfNecessary(
            Long courseId,
            String seatKey,
            RPermitExpirableSemaphore semaphore
    ) {
        if (semaphore.isExists()) {
            return;
        }

        RLock initializationLock =
                redissonClient.getLock(seatKey + ":initialization");

        initializationLock.lock();

        try {
            if (semaphore.isExists()) {
                return;
            }

            Course course = courseRepository.findById(courseId)
                    .orElseThrow(() -> new EnrollmentException(
                            "COURSE_NOT_FOUND",
                            "과목 정보를 찾을 수 없습니다.",
                            HttpStatus.NOT_FOUND
                    ));

            int remainingSeats = Math.max(
                    course.getCapacity() - course.getEnrolledCount(),
                    0
            );

            semaphore.trySetPermits(remainingSeats);

        } finally {
            if (initializationLock.isHeldByCurrentThread()) {
                initializationLock.unlock();
            }
        }
    }

    private String seatKey(Long courseId) {
        return SEAT_KEY_PREFIX + courseId + SEAT_KEY_SUFFIX;
    }
}
