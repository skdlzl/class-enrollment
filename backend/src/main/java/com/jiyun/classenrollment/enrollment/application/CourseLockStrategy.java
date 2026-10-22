package com.jiyun.classenrollment.enrollment.application;

public enum CourseLockStrategy {
    // Since failure-safety-v1 all modes retain student/course DB write guards.
    // REDIS adds student + course Redis admission; it is no longer a Redis-only write lock benchmark.
    REDIS,
    // Student Redis admission + student/course DB safety locks.
    PESSIMISTIC,
    DB_ONLY
}
