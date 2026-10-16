package com.jiyun.classenrollment.enrollment.queue;

import com.jiyun.classenrollment.enrollment.api.EnrollmentRequest;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/enrollment-requests")
@ConditionalOnProperty(name="enrollment.queue.enabled", havingValue="true")
public class EnrollmentQueueController {
    private final EnrollmentRequestStore store;
    private final JdbcTemplate jdbc;
    public EnrollmentQueueController(EnrollmentRequestStore store, JdbcTemplate jdbc) {this.store=store;this.jdbc=jdbc;}
    @PostMapping @ResponseStatus(HttpStatus.ACCEPTED)
    public EnrollmentTicket submit(@Valid @RequestBody EnrollmentRequest request) {
        return store.submit(request.studentId(),request.courseId());
    }
    @GetMapping("/{id}") public EnrollmentTicket get(@PathVariable String id) {return store.get(id);}
    @GetMapping public List<EnrollmentTicket> list(@RequestParam long studentId) {return store.forStudent(studentId);}
    @PostMapping("/{id}/retry") @ResponseStatus(HttpStatus.ACCEPTED)
    public EnrollmentTicket retry(@PathVariable String id) {return store.retry(id);}
    @GetMapping("/snapshot") public Map<String,Object> snapshot(@RequestParam long studentId) {
        return Map.of("tickets",store.forStudent(studentId),"enrollments",jdbc.queryForList("""
            SELECT c.id,c.name,c.credits FROM enrollments e JOIN courses c ON c.id=e.course_id
            WHERE e.student_id=? ORDER BY e.id
            """,studentId));
    }
    // Demo selectors use actual DB identifiers, not the old browser-only mock IDs.
    @GetMapping("/options") public Map<String,Object> options() {
        return Map.of("students",jdbc.queryForList("SELECT id,name,max_credits AS maxCredits FROM students ORDER BY id LIMIT 100"),
            "schedules",jdbc.queryForList("SELECT course_id AS courseId,day_of_week AS day,DATE_FORMAT(start_time, '%H:%i') AS start,DATE_FORMAT(end_time, '%H:%i') AS end FROM course_schedules WHERE course_id IN (SELECT id FROM (SELECT id FROM courses WHERE academic_year=2026 AND semester=2 ORDER BY id LIMIT 100) selected_courses) ORDER BY course_id,id"),
            "courses",jdbc.queryForList("""
                SELECT id,course_code AS code,name,professor,department,credits,capacity,
                enrolled_count AS enrolled FROM courses WHERE academic_year=2026 AND semester=2 ORDER BY id LIMIT 100
                """));
    }
}
