package com.jiyun.classenrollment.enrollment.queue;

public record EnrollmentTicket(long id, String requestId, long studentId, long courseId,
        String courseName, int credits, String status, int attempts, Long enrollmentId,
        String message, String createdAt) {
    public boolean terminal() {
        return status.equals("CONFIRMED") || status.equals("REJECTED") || status.equals("FAILED");
    }
}
