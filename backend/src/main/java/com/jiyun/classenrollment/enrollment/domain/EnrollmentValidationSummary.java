package com.jiyun.classenrollment.enrollment.domain;

public interface EnrollmentValidationSummary {

    Long getDuplicateEnrollment();

    int getCurrentCredits();

    Long getScheduleConflict();
}
