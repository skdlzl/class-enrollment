package com.jiyun.classenrollment.enrollment.domain;

public interface EnrollmentValidationSummary {

    boolean getDuplicateEnrollment();

    int getCurrentCredits();

    boolean getScheduleConflict();
}
