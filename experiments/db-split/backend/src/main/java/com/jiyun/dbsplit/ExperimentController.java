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
    private final JdbcTemplate jdbc, academic;private final EnrollmentFacade service;private final FixtureService fixture;
    private final Metrics metrics;private final HikariDataSource pool;private final String revision,session,policy;
    private final DatabaseConfig.Mode mode;
    private final com.jiyun.dbsplit.lockapi.LockApiEnrollment lockApi;
    private final org.springframework.core.env.Environment environment;
    public ExperimentController(JdbcTemplate jdbc,@org.springframework.beans.factory.annotation.Qualifier("academicJdbc") JdbcTemplate academic,
        EnrollmentFacade service,FixtureService fixture,Metrics metrics,
        HikariDataSource pool,DatabaseConfig.Mode mode,@Value("${experiment.revision}") String revision,
        @Value("${experiment.session:legacy}") String session,@Value("${experiment.dataset-policy:append}") String policy,org.springframework.core.env.Environment environment,com.jiyun.dbsplit.lockapi.LockApiEnrollment lockApi){
        this.lockApi=lockApi;
        this.environment=environment;
        this.jdbc=jdbc;this.academic=academic;this.service=service;this.fixture=fixture;this.metrics=metrics;
        this.pool=pool;this.mode=mode;this.revision=revision;this.session=session;this.policy=policy;
    }
    @PostMapping("/experiment/fixtures") public Map<String,Object> fixtures(){return fixture.create();}
    @PostMapping("/experiment/fixtures/{runId}/reset") public Map<String,Object> reset(@PathVariable String runId){return fixture.reset(runId);}
    @GetMapping("/experiment/integrity") public Map<String,Object> integrity(@RequestParam String runId){return fixture.integrity(runId);}
    @GetMapping("/experiment/enrollment-records") public List<Map<String,Object>> enrollmentRecords(@RequestParam String runId){
        return jdbc.queryForList("SELECT request_id,student_id,course_id,status FROM enrollment WHERE run_id=?",runId);
    }
    @GetMapping("/experiment/pool-samples") public List<Map<String,Object>> poolSamples(){return metrics.poolSamples();}
    @GetMapping("/experiment/state") public Map<String,Object> state(){
        var result=new LinkedHashMap<String,Object>();
        result.put("pid",ProcessHandle.current().pid());result.put("version","db-split-v2");result.put("mode",mode.name());result.put("layout",(mode==DatabaseConfig.Mode.B||mode==DatabaseConfig.Mode.C||mode==DatabaseConfig.Mode.D||mode==DatabaseConfig.Mode.E)?"SPLIT":"SINGLE");
        result.put("strategy",mode==DatabaseConfig.Mode.E?"SETNX_GUARDED":mode==DatabaseConfig.Mode.C?"REDIS_GUARDED":mode==DatabaseConfig.Mode.D?"HYBRID_GUARDED":"DB_ONLY");
        result.put("inFlight",service.inFlight()+lockApi.inFlight());
        result.put("lockApi",lockApi.state());
        if(lockApi.enabled())result.put("strategy","LOCK_API_"+lockApi.state().get("strategy"));
        result.put("notifications",Map.of("enabled",environment.getProperty("experiment.notifications.enabled",Boolean.class,false),"auto",environment.getProperty("experiment.notifications.auto",Boolean.class,false),"strategy",environment.getProperty("experiment.notifications.strategy","DB_CLAIM")));
        result.put("redisGuard",Map.of("enabled",mode==DatabaseConfig.Mode.C||mode==DatabaseConfig.Mode.D||mode==DatabaseConfig.Mode.E,"student",mode==DatabaseConfig.Mode.C||mode==DatabaseConfig.Mode.D||mode==DatabaseConfig.Mode.E,"course",mode==DatabaseConfig.Mode.C||mode==DatabaseConfig.Mode.E,"waitBudgetMs",10000,"watchdogMs",mode==DatabaseConfig.Mode.E?0:30000,"leaseMs",mode==DatabaseConfig.Mode.E?30000:0,"pollMinMs",mode==DatabaseConfig.Mode.E?20:0,"pollMaxMs",mode==DatabaseConfig.Mode.E?40:0));result.put("revision",revision);result.put("session",session);result.put("datasetPolicy",policy);
        result.put("metrics",metrics.snapshot());result.put("pools",metrics.poolState());
        result.put("isolation",pool.getTransactionIsolation());
        result.put("guards",List.of("student-db-lock","course-db-lock","unique-request","unique-enrollment"));
        result.put("databases",Map.of("enrollment",jdbc.queryForMap("SELECT @@server_uuid AS serverUuid,DATABASE() AS databaseName"),
            "academic",academic.queryForMap("SELECT @@server_uuid AS serverUuid,DATABASE() AS databaseName")));
        if(lockApi.enabled())result.put("redisGuard",Map.of("enabled",Set.of("REDIS","HYBRID").contains(lockApi.state().get("strategy")),"student",Set.of("REDIS","HYBRID").contains(lockApi.state().get("strategy")),"course","REDIS".equals(lockApi.state().get("strategy")),"waitBudgetMs",10000,"watchdogMs",30000));
        if(lockApi.enabled())result.put("guards",List.of("lock-api-"+lockApi.state().get("strategy"),"unique-request","unique-enrollment","db-check-constraints"));
        if("HYBRID".equals(lockApi.state().get("strategy")))result.put("guards",List.of("student-redis-lock","student-db-revalidation","course-db-lock","unique-request","unique-enrollment","db-check-constraints"));
        return result;
    }
    @GetMapping("/api/subjects") public List<Map<String,Object>> subjects(@RequestParam String runId,
        @RequestParam(defaultValue="D0") String department,@RequestParam(defaultValue="0") long afterId){
        return metrics.time("academicRead",()->academic.queryForList("SELECT id,code,name,description FROM subject WHERE run_id=? AND department=? AND id>? ORDER BY id LIMIT 50",runId,department,afterId));
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
        validate(r);return ResponseEntity.status(201).body(lockApi.enabled()?lockApi.enroll(r):service.enroll(r));
    }
    @PostMapping("/api/cancellations") public EnrollmentService.Result cancel(@RequestBody EnrollmentService.Request r){
        validate(r);if(lockApi.enabled())throw new com.jiyun.dbsplit.lockapi.LockApiEnrollment.Unavailable("LOCK_COMPARISON_ENROLL_ONLY");return service.cancel(r);
    }
    @GetMapping("/api/enrollments/{id}") public ResponseEntity<?> result(@PathVariable String id){
        var rows=jdbc.queryForList("SELECT request_id,status,student_id,course_id FROM enrollment WHERE request_id=?",id);
        // A temporary 404 is not proof that an in-flight transaction will never commit.
        return rows.isEmpty()?ResponseEntity.notFound().build():ResponseEntity.ok(rows.get(0));
    }
    @ExceptionHandler(com.jiyun.dbsplit.lockapi.LockApiEnrollment.Unavailable.class) ResponseEntity<?> lockApiUnavailable(com.jiyun.dbsplit.lockapi.LockApiEnrollment.Unavailable e){return ResponseEntity.status(503).body(Map.of("code",e.code));}
    @ExceptionHandler(EnrollmentFacade.Unavailable.class) ResponseEntity<?> unavailable(EnrollmentFacade.Unavailable e){return ResponseEntity.status(503).body(Map.of("code",e.code));}
    @ExceptionHandler(EnrollmentService.Rejected.class) ResponseEntity<?> rejected(EnrollmentService.Rejected e){return ResponseEntity.status(409).body(Map.of("code",e.code));}
    @ExceptionHandler(DuplicateKeyException.class) ResponseEntity<?> duplicate(DuplicateKeyException e){return ResponseEntity.status(409).body(Map.of("code","REQUEST_OR_ENROLLMENT_CONFLICT"));}
    @ExceptionHandler({DataAccessException.class,TransactionException.class}) ResponseEntity<?> unknown(RuntimeException e){
        // Connection/commit exceptions can have ambiguous outcomes; callers must reconcile by request ID.
        metrics.record("databaseError",0,true);
        return ResponseEntity.status(503).body(Map.of("code","DATABASE_RESULT_UNKNOWN"));
    }
}
