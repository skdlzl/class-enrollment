package com.jiyun.classenrollment.enrollment.application;

public enum CourseLockStrategy {
    REDIS,
    PESSIMISTIC,
    DB_ONLY
}
