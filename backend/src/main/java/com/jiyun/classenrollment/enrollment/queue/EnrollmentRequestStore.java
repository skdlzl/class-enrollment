package com.jiyun.classenrollment.enrollment.queue;

import org.springframework.stereotype.Repository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.UUID;

@Repository
public class EnrollmentRequestStore {
    private final JdbcTemplate jdbc;
    public EnrollmentRequestStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private static final String SELECT = """
        SELECT r.*, c.name AS course_name, c.credits FROM enrollment_requests r
        JOIN courses c ON c.id=r.course_id
        """;
    private List<EnrollmentTicket> query(String suffix, Object... args) {
        return jdbc.query(SELECT + suffix, (rs,n) -> new EnrollmentTicket(
            rs.getLong("id"), rs.getString("request_id"), rs.getLong("student_id"),
            rs.getLong("course_id"), rs.getString("course_name"), rs.getInt("credits"),
            rs.getString("status"), rs.getInt("attempts"), rs.getObject("enrollment_id", Long.class),
            rs.getString("message"), rs.getTimestamp("created_at").toInstant().toString()), args);
    }
    public EnrollmentTicket submit(long student, long course) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM students WHERE id=?", Integer.class, student)==0
                || jdbc.queryForObject("SELECT COUNT(*) FROM courses WHERE id=?", Integer.class, course)==0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "학생 또는 과목을 찾을 수 없습니다.");
        }
        // Durable receipt first. Unique pair makes a lost POST response safe to retry.
        try {
            jdbc.update("INSERT INTO enrollment_requests(request_id,student_id,course_id) VALUES(?,?,?)",
                UUID.randomUUID().toString(), student, course);
        } catch (DuplicateKeyException alreadySubmitted) { /* return original receipt */ }
        return query(" WHERE r.student_id=? AND r.course_id=?",student,course).get(0);
    }
    public EnrollmentTicket get(String id) {
        return query(" WHERE r.request_id=?", id).stream().findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"접수를 찾을 수 없습니다."));
    }
    public List<EnrollmentTicket> forStudent(long student) {
        return query(" WHERE r.student_id=? ORDER BY r.id DESC LIMIT 100", student);
    }
    public List<EnrollmentTicket> recoverable() {
        return query(" WHERE r.status IN ('QUEUED','PROCESSING') AND r.next_attempt_at<=CURRENT_TIMESTAMP(6) ORDER BY r.id LIMIT 200");
    }
    public boolean isFullWithoutEnrollment(long studentId, long courseId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT c.enrolled_count>=c.capacity AND NOT EXISTS (
                SELECT 1 FROM enrollments e WHERE e.student_id=? AND e.course_id=c.id
            ) FROM courses c WHERE c.id=?
            """, Boolean.class,studentId,courseId));
    }
    public boolean start(String id) {
        return jdbc.update("""
            UPDATE enrollment_requests SET status='PROCESSING', attempts=attempts+1,
            message='신청 조건을 확인하고 있습니다.', updated_at=CURRENT_TIMESTAMP(6),
            next_attempt_at=TIMESTAMPADD(SECOND, 60, CURRENT_TIMESTAMP(6))
            WHERE request_id=? AND status IN ('QUEUED','PROCESSING') AND next_attempt_at<=CURRENT_TIMESTAMP(6)
            """, id)==1;
    }
    public void reject(String id, String message) { finish(id,"REJECTED",message); }
    private void finish(String id, String status, String message) {
        jdbc.update("""
            UPDATE enrollment_requests SET status=?,message=?,updated_at=CURRENT_TIMESTAMP(6)
            WHERE request_id=? AND status='PROCESSING'
            """, status, message, id);
    }
    public void retryLater(String id, int maxAttempts) {
        jdbc.update("""
            UPDATE enrollment_requests SET status=CASE WHEN attempts>=? THEN 'FAILED' ELSE 'QUEUED' END,
            message=CASE WHEN attempts>=? THEN '일시적인 문제로 처리를 완료하지 못했습니다. 다시 시도해 주세요.'
            ELSE '처리가 지연되어 자동으로 다시 시도하고 있습니다.' END,
            next_attempt_at=TIMESTAMPADD(SECOND, LEAST(30, attempts*5), CURRENT_TIMESTAMP(6)),
            updated_at=CURRENT_TIMESTAMP(6) WHERE request_id=? AND status='PROCESSING'
            """, maxAttempts,maxAttempts,id);
    }
    public EnrollmentTicket retry(String id) {
        jdbc.update("""
            UPDATE enrollment_requests SET status='QUEUED',attempts=0,message='다시 접수되었습니다.',
            next_attempt_at=CURRENT_TIMESTAMP(6),updated_at=CURRENT_TIMESTAMP(6)
            WHERE request_id=? AND status='FAILED'
            """, id);
        return get(id);
    }
}
