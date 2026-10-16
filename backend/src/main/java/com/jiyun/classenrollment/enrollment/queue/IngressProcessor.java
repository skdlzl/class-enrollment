package com.jiyun.classenrollment.enrollment.queue;

import com.jiyun.classenrollment.common.error.EnrollmentException;
import com.jiyun.classenrollment.enrollment.application.EnrollmentService;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.http.HttpStatus;
import java.util.List;

@Service
public class IngressProcessor {
    private final JdbcTemplate jdbc;
    private final EnrollmentService enrollment;
    private final TransactionTemplate tx;
    public IngressProcessor(JdbcTemplate jdbc,EnrollmentService enrollment,PlatformTransactionManager manager){
        this.jdbc=jdbc;this.enrollment=enrollment;this.tx=new TransactionTemplate(manager);
        this.tx.setTimeout(15);
        this.tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public EnrollmentTicket process(EnrollmentTicket input){
        try{return tx.execute(status->{
            EnrollmentTicket current=lockReceipt(input);
            if(current.terminal())return current;
            // DB row locks remain the final guard if a Redis lease expires.
            var student=jdbc.queryForList("SELECT id FROM students WHERE id=? FOR UPDATE",Long.class,input.studentId());
            if(student.isEmpty())throw new EnrollmentException("STUDENT_NOT_FOUND","학생을 찾을 수 없습니다.",HttpStatus.NOT_FOUND);
            var courses=jdbc.queryForList("SELECT id FROM courses WHERE id=? FOR UPDATE",Long.class,input.courseId());
            if(courses.isEmpty())throw new EnrollmentException("COURSE_NOT_FOUND","과목을 찾을 수 없습니다.",HttpStatus.NOT_FOUND);
            var existing=jdbc.queryForList("SELECT id FROM enrollments WHERE student_id=? AND course_id=?",Long.class,input.studentId(),input.courseId());
            long enrollmentId=existing.isEmpty()?enrollment.enroll(input.studentId(),input.courseId()):existing.get(0);
            return save(input,"CONFIRMED",enrollmentId,"수강신청이 확정되었습니다.");
        });}catch(EnrollmentException e){
            if(!e.getStatus().is4xxClientError())throw e;
            // The failed validation transaction has rolled back before persisting rejection.
            return tx.execute(status->{var current=lockReceipt(input);return current.terminal()?current:save(input,"REJECTED",null,e.getMessage());});
        }
    }
    private EnrollmentTicket lockReceipt(EnrollmentTicket input){
        jdbc.update("""
            INSERT INTO enrollment_ingress_results(request_id,student_id,course_id,status,created_at)
            VALUES(?,?,?,'PROCESSING',?) ON DUPLICATE KEY UPDATE request_id=request_id
            """,input.requestId(),input.studentId(),input.courseId(),java.sql.Timestamp.from(java.time.Instant.parse(input.createdAt())));
        jdbc.queryForObject("SELECT request_id FROM enrollment_ingress_results WHERE request_id=? FOR UPDATE",String.class,input.requestId());
        return get(input.requestId(),input.attempts());
    }
    private EnrollmentTicket save(EnrollmentTicket t,String status,Long id,String message){
        jdbc.update("UPDATE enrollment_ingress_results SET status=?,enrollment_id=?,message=? WHERE request_id=?",status,id,message,t.requestId());
        return get(t.requestId(),t.attempts());
    }
    public EnrollmentTicket get(String id,int attempts){
        return jdbc.query("""
            SELECT r.*,COALESCE(c.name,'') course_name,COALESCE(c.credits,0) credits
            FROM enrollment_ingress_results r LEFT JOIN courses c ON c.id=r.course_id WHERE request_id=?
            """,(rs,n)->new EnrollmentTicket(0,rs.getString("request_id"),rs.getLong("student_id"),rs.getLong("course_id"),
            rs.getString("course_name"),rs.getInt("credits"),rs.getString("status"),attempts,rs.getObject("enrollment_id",Long.class),
            rs.getString("message"),rs.getTimestamp("created_at").toInstant().toString()),id).stream().findFirst().orElse(null);
    }
}
