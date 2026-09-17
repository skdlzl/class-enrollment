package com.jiyun.classenrollment.enrollment.api;

import jakarta.validation.constraints.NotNull;

public record EnrollmentRequest(@NotNull Long studentId, @NotNull Long courseId) {
}
