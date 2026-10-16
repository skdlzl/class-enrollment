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
    private final RedisIngressStore store;
    private final JdbcTemplate jdbc;
    public EnrollmentQueueController(RedisIngressStore store, JdbcTemplate jdbc) {this.store=store;this.jdbc=jdbc;}
    @ExceptionHandler(org.redisson.client.RedisException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String,String> unavailable(org.redisson.client.RedisException error){
        return Map.of("message","접수 상태를 확인할 수 없습니다. 같은 신청으로 다시 확인해 주세요.");
    }
    @PostMapping @ResponseStatus(HttpStatus.ACCEPTED)
    public EnrollmentTicket submit(@Valid @RequestBody EnrollmentRequest request) {
        var ticket=store.submit(request.studentId(),request.courseId());
        return ticket;
    }
    @GetMapping("/{id}") public EnrollmentTicket get(@PathVariable String id) {return store.get(id);}
    @GetMapping public List<EnrollmentTicket> list(@RequestParam long studentId) {return store.forStudent(studentId);}
    @PostMapping("/{id}/retry") @ResponseStatus(HttpStatus.ACCEPTED)
    public EnrollmentTicket retry(@PathVariable String id) {return store.retry(id);}
    @GetMapping("/health") public Map<String,Object> health(){return Map.of("mode","redis-ingress-v1","pending",store.pending());}
    @GetMapping("/snapshot") public Map<String,Object> snapshot(@RequestParam long studentId,
            @RequestParam(defaultValue="false") boolean includeEnrollments) {
        var tickets=store.forStudent(studentId);
        var confirmed=includeEnrollments?jdbc.queryForList("""
            SELECT c.id,c.name,c.credits FROM enrollments e JOIN courses c ON c.id=e.course_id
            WHERE e.student_id=? ORDER BY e.id
            """,studentId):tickets.stream().filter(t->t.status().equals("CONFIRMED"))
                .map(t->Map.<String,Object>of("id",t.courseId(),"name",t.courseName(),"credits",t.credits())).toList();
        return Map.of("tickets",tickets,"enrollments",confirmed);
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
