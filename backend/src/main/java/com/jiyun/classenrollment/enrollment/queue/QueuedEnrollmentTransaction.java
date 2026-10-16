package com.jiyun.classenrollment.enrollment.queue;

import com.jiyun.classenrollment.enrollment.application.EnrollmentService;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QueuedEnrollmentTransaction {
    private final QueueTimings timings=new QueueTimings();
    public void logTimings() {timings.logSnapshot();}
    private <T> T measured(String phase, java.util.function.Supplier<T> operation) {
        long start=System.nanoTime();
        try {return operation.get();} finally {timings.record(phase,System.nanoTime()-start);}
    }
    private final JdbcTemplate jdbc;
    private final EnrollmentService enrollment;
    public QueuedEnrollmentTransaction(JdbcTemplate jdbc, EnrollmentService enrollment) {
        this.jdbc=jdbc; this.enrollment=enrollment;
    }
    @Transactional
    public void confirm(EnrollmentTicket ticket) {
        String status=measured("tx.requestLock",()->jdbc.queryForObject("SELECT status FROM enrollment_requests WHERE request_id=? FOR UPDATE",
            String.class,ticket.requestId()));
        if (!"PROCESSING".equals(status)) return;
        // Final DB guards also protect against a stale Redis lock owner resuming.
        // All queued writers use student -> course ordering, then validate and write.
        measured("tx.studentLock",()->jdbc.queryForObject("SELECT id FROM students WHERE id=? FOR UPDATE",Long.class,ticket.studentId()));
        measured("tx.courseLock",()->jdbc.queryForObject("SELECT id FROM courses WHERE id=? FOR UPDATE",Long.class,ticket.courseId()));
        var existing=measured("tx.existing",()->jdbc.queryForList("SELECT id FROM enrollments WHERE student_id=? AND course_id=?",
            Long.class,ticket.studentId(),ticket.courseId()));
        long enrollmentId=existing.isEmpty() ? measured("tx.validateAndSave",()->enrollment.enroll(ticket.studentId(),ticket.courseId())) : existing.get(0);
        measured("tx.confirmReceipt",()->jdbc.update("""
            UPDATE enrollment_requests SET status='CONFIRMED',enrollment_id=?,
            message='수강신청이 확정되었습니다.',updated_at=CURRENT_TIMESTAMP(6) WHERE request_id=?
            """,enrollmentId,ticket.requestId()));
    }
}
