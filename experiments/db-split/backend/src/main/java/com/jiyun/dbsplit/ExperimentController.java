package com.jiyun.dbsplit;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
public class ExperimentController {
    private final JdbcTemplate jdbc;private final EnrollmentService service;private final FixtureService fixture;
    private final Metrics metrics;private final HikariDataSource pool;private final String revision;
    public ExperimentController(JdbcTemplate jdbc,EnrollmentService service,FixtureService fixture,Metrics metrics,
        HikariDataSource pool,@Value("${experiment.revision}") String revision){
        this.jdbc=jdbc;this.service=service;this.fixture=fixture;this.metrics=metrics;this.pool=pool;this.revision=revision;
    }
    @PostMapping("/experiment/fixtures") public Map<String,Object> fixtures(){return fixture.create();}
    @GetMapping("/experiment/integrity") public Map<String,Object> integrity(@RequestParam String runId){return fixture.integrity(runId);}
    @GetMapping("/experiment/pool-samples") public List<Map<String,Object>> poolSamples(){return metrics.poolSamples();}
    @GetMapping("/experiment/state") public Map<String,Object> state(){
        var p=pool.getHikariPoolMXBean();
        return Map.of("version","db-split-stage-a-v1","layout","SINGLE","strategy","DB_ONLY","revision",revision,
          "metrics",metrics.snapshot(),"pool",Map.of("max",pool.getMaximumPoolSize(),"active",p==null?0:p.getActiveConnections(),
          "waiting",p==null?0:p.getThreadsAwaitingConnection(),"connectionTimeoutMs",pool.getConnectionTimeout()),
          "isolation",pool.getTransactionIsolation(),"guards",List.of("student-db-lock","course-db-lock","unique-request","unique-enrollment"));
    }
    @GetMapping("/api/subjects") public List<Map<String,Object>> subjects(@RequestParam String runId,
        @RequestParam(defaultValue="D0") String department,@RequestParam(defaultValue="0") long afterId){
        return metrics.time("academicRead",()->jdbc.queryForList("SELECT id,code,name,description FROM subject WHERE run_id=? AND department=? AND id>? ORDER BY id LIMIT 50",runId,department,afterId));
    }
    @GetMapping("/api/offerings") public List<Map<String,Object>> offerings(@RequestParam String runId,
        @RequestParam(defaultValue="D0") String department,@RequestParam(defaultValue="1") int grade,
        @RequestParam(defaultValue="0") long afterId,@RequestParam(defaultValue="") String keyword){
        if(department.length()>20||grade<1||grade>4||afterId<0||keyword.length()>100)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        String literal="%"+keyword.strip().replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        return metrics.time("offeringRead",()->jdbc.queryForList("""
          SELECT c.id,c.name,c.credits,c.capacity,c.enrolled_count AS enrolledCount,s.day_no AS dayNo,s.start_minute AS startMinute,s.end_minute AS endMinute
          FROM offered_course c JOIN offering_schedule s ON s.run_id=c.run_id AND s.course_id=c.id
          WHERE c.run_id=? AND c.term_id=202602 AND c.department=? AND c.grade=? AND c.id>? AND c.name LIKE ? ESCAPE '!'
          ORDER BY c.id,s.day_no,s.start_minute LIMIT 50
          """,runId,department,grade,afterId,literal));
    }
    private void validate(EnrollmentService.Request r){
        try{UUID.fromString(r.requestId());UUID.fromString(r.runId());}
        catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid request/run ID");}
        if(r.studentId()<1||r.courseId()<1||r.termId()!=202602)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }
    @PostMapping("/api/enrollments") public ResponseEntity<EnrollmentService.Result> enroll(@RequestBody EnrollmentService.Request r){
        validate(r);return ResponseEntity.status(201).body(metrics.time("enrollment.transactionProxy",()->service.enroll(r)));
    }
    @PostMapping("/api/cancellations") public EnrollmentService.Result cancel(@RequestBody EnrollmentService.Request r){
        validate(r);return metrics.time("cancellation.transactionProxy",()->service.cancel(r));
    }
    @GetMapping("/api/enrollments/{id}") public ResponseEntity<?> result(@PathVariable String id){
        var rows=jdbc.queryForList("SELECT request_id,status,student_id,course_id FROM enrollment WHERE request_id=?",id);
        // A temporary 404 is not proof that an in-flight transaction will never commit.
        return rows.isEmpty()?ResponseEntity.notFound().build():ResponseEntity.ok(rows.get(0));
    }
    @ExceptionHandler(EnrollmentService.Rejected.class) ResponseEntity<?> rejected(EnrollmentService.Rejected e){return ResponseEntity.status(409).body(Map.of("code",e.code));}
    @ExceptionHandler(DuplicateKeyException.class) ResponseEntity<?> duplicate(DuplicateKeyException e){return ResponseEntity.status(409).body(Map.of("code","REQUEST_OR_ENROLLMENT_CONFLICT"));}
    @ExceptionHandler({DataAccessException.class,TransactionException.class}) ResponseEntity<?> unknown(RuntimeException e){
        // Connection/commit exceptions can have ambiguous outcomes; callers must reconcile by request ID.
        metrics.record("databaseError",0,true);
        return ResponseEntity.status(503).body(Map.of("code","DATABASE_RESULT_UNKNOWN"));
    }
}
