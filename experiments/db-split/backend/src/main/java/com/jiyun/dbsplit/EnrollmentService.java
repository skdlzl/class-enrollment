package com.jiyun.dbsplit;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import java.util.*;

@Service
public class EnrollmentService {
    public record Request(String requestId,String runId,long studentId,int termId,long courseId){}
    public record Result(String requestId,String status){}
    public static class Rejected extends RuntimeException {
        public final String code;public Rejected(String code){super(code);this.code=code;}
    }
    private final JdbcTemplate jdbc;private final Metrics metrics;
    private final com.jiyun.dbsplit.notification.NotificationOutbox outbox;
    public EnrollmentService(JdbcTemplate jdbc,Metrics metrics,com.jiyun.dbsplit.notification.NotificationOutbox outbox){this.jdbc=jdbc;this.metrics=metrics;this.outbox=outbox;}
    private Map<String,Object> student(Request r){
        var rows=metrics.time("studentLock.sql",()->jdbc.queryForList("SELECT status,max_credits,enrolled_credits FROM student_term WHERE run_id=? AND student_id=? AND term_id=? FOR UPDATE",r.runId(),r.studentId(),r.termId()));
        if(rows.isEmpty())throw new Rejected("STUDENT_NOT_FOUND");return rows.get(0);
    }
    private void courseLock(Request r){
        var rows=metrics.time("courseLock.sql",()->jdbc.queryForList("SELECT capacity,enrolled_count FROM offered_course WHERE run_id=? AND id=? AND term_id=? FOR UPDATE",r.runId(),r.courseId(),r.termId()));
        if(rows.isEmpty())throw new Rejected("COURSE_NOT_FOUND");
        long acquired=System.nanoTime();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            public void afterCompletion(int status){metrics.record("courseLock.holdUntilCompletion",System.nanoTime()-acquired,false);}
        });
        if(((Number)rows.get(0).get("enrolled_count")).intValue()>=((Number)rows.get(0).get("capacity")).intValue())throw new Rejected("COURSE_FULL");
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Result enroll(Request r){
        var s=student(r); // First read is a locking read; serialize all changes for this student/term.
        var known=jdbc.queryForList("SELECT run_id,student_id,term_id,course_id,status FROM enrollment WHERE request_id=?",r.requestId());
        if(!known.isEmpty()){
            var k=known.get(0);
            if(!r.runId().equals(k.get("run_id"))||r.studentId()!=((Number)k.get("student_id")).longValue()||r.termId()!=((Number)k.get("term_id")).intValue()||r.courseId()!=((Number)k.get("course_id")).longValue())throw new Rejected("REQUEST_ID_CONFLICT");
            return new Result(r.requestId(),(String)k.get("status"));
        }
        int credits=metrics.time("validation",()->{
            if(!"ACTIVE".equals(s.get("status")))throw new Rejected("STUDENT_NOT_ACTIVE");
            var courses=jdbc.queryForList("SELECT credits FROM offered_course WHERE run_id=? AND id=? AND term_id=?",r.runId(),r.courseId(),r.termId());
            if(courses.isEmpty())throw new Rejected("COURSE_NOT_FOUND");
            int value=((Number)courses.get(0).get("credits")).intValue();
            if(jdbc.queryForObject("SELECT COUNT(*) FROM enrollment WHERE run_id=? AND student_id=? AND term_id=? AND course_id=?",Integer.class,r.runId(),r.studentId(),r.termId(),r.courseId())>0)throw new Rejected("DUPLICATE_ENROLLMENT");
            if(((Number)s.get("enrolled_credits")).intValue()+value>((Number)s.get("max_credits")).intValue())throw new Rejected("CREDIT_LIMIT_EXCEEDED");
            int conflicts=jdbc.queryForObject("""
                SELECT COUNT(*) FROM enrollment e JOIN offering_schedule own ON own.run_id=e.run_id AND own.course_id=e.course_id
                JOIN offering_schedule target ON target.run_id=e.run_id AND target.course_id=? AND target.day_no=own.day_no
                AND target.start_minute<own.end_minute AND own.start_minute<target.end_minute
                WHERE e.run_id=? AND e.student_id=? AND e.term_id=? AND e.status='CONFIRMED'
                """,Integer.class,r.courseId(),r.runId(),r.studentId(),r.termId());
            if(conflicts>0)throw new Rejected("SCHEDULE_CONFLICT");return value;
        });
        courseLock(r);
        jdbc.update("INSERT INTO enrollment(request_id,run_id,student_id,term_id,course_id) VALUES(?,?,?,?,?)",r.requestId(),r.runId(),r.studentId(),r.termId(),r.courseId());
        jdbc.update("UPDATE offered_course SET enrolled_count=enrolled_count+1 WHERE run_id=? AND id=?",r.runId(),r.courseId());
        jdbc.update("UPDATE student_term SET enrolled_credits=enrolled_credits+? WHERE run_id=? AND student_id=? AND term_id=?",credits,r.runId(),r.studentId(),r.termId());
        outbox.enqueue(r,"CONFIRMED");
        return new Result(r.requestId(),"CONFIRMED");
    }
    @Transactional
    public Result cancel(Request r){
        student(r);
        var rows=jdbc.queryForList("SELECT request_id,status FROM enrollment WHERE run_id=? AND student_id=? AND term_id=? AND course_id=?",r.runId(),r.studentId(),r.termId(),r.courseId());
        if(rows.isEmpty())throw new Rejected("ENROLLMENT_NOT_FOUND");
        String id=(String)rows.get(0).get("request_id");
        if("CANCELLED".equals(rows.get(0).get("status")))return new Result(id,"CANCELLED");
        int credits=jdbc.queryForObject("SELECT credits FROM offered_course WHERE run_id=? AND id=? FOR UPDATE",Integer.class,r.runId(),r.courseId());
        jdbc.update("UPDATE enrollment SET status='CANCELLED' WHERE request_id=?",id);
        jdbc.update("UPDATE offered_course SET enrolled_count=enrolled_count-1 WHERE run_id=? AND id=?",r.runId(),r.courseId());
        jdbc.update("UPDATE student_term SET enrolled_credits=enrolled_credits-? WHERE run_id=? AND student_id=? AND term_id=?",credits,r.runId(),r.studentId(),r.termId());
        outbox.enqueue(new Request(id,r.runId(),r.studentId(),r.termId(),r.courseId()),"CANCELLED");
        return new Result(id,"CANCELLED");
    }
}
