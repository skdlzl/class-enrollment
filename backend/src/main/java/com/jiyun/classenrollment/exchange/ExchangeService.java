package com.jiyun.classenrollment.exchange;

import com.jiyun.classenrollment.common.error.EnrollmentException;
import com.jiyun.classenrollment.enrollment.v2.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ExchangeService {
    public record View(String requestId, String phase, Long enrollmentId, String lastError) {}
    private final LocalAdmission admission = new LocalAdmission();
    private final JdbcTemplate db;
    private final RedisGate gate;
    private final PartnerClient partner;
    private final EnrollmentCoordinator enrollment;
    public ExchangeService(JdbcTemplate db,RedisGate gate,PartnerClient partner,EnrollmentCoordinator enrollment) {
        this.db=db; this.gate=gate; this.partner=partner; this.enrollment=enrollment;
    }
    public View submit(long student,long course,String key) {
        if(student<=0 || course<=0 || key==null || !key.matches("[A-Za-z0-9._:-]{1,80}"))
            throw new EnrollmentException("INVALID_REQUEST","양수 ID와 1~80자 멱등 키가 필요합니다.",HttpStatus.BAD_REQUEST);
        String id=UUID.randomUUID().toString();
        db.update("INSERT INTO exchange_requests(id,student_id,course_id,request_key) VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE id=id",id,student,course,key);
        var row=db.queryForMap("SELECT * FROM exchange_requests WHERE student_id=? AND request_key=?",student,key);
        if(((Number)row.get("course_id")).longValue()!=course)
            throw new EnrollmentException("IDEMPOTENCY_KEY_REUSED","다른 강좌에 사용한 키입니다.",HttpStatus.CONFLICT);
        id=(String)row.get("id");
        process(id);
        return get(id);
    }
    public View get(String id) {
        var rows=db.queryForList("SELECT * FROM exchange_requests WHERE id=?",id);
        if(rows.isEmpty()) throw new EnrollmentException("REQUEST_NOT_FOUND","신청이 없습니다.",HttpStatus.NOT_FOUND);
        var r=rows.get(0);
        return new View(id,(String)r.get("phase"),r.get("enrollment_id")==null?null:((Number)r.get("enrollment_id")).longValue(),(String)r.get("last_error"));
    }
    public void recover() {
        for(String id:db.queryForList("SELECT id FROM exchange_requests WHERE phase IN ('RESERVING','CONFIRMING','CANCELLING') AND next_attempt<=NOW(6) AND (owner IS NULL OR claim_until<NOW(6)) ORDER BY next_attempt LIMIT 16",String.class))
            process(id);
    }
    public void process(String id) {
        String owner=UUID.randomUUID().toString();
        if(db.update("UPDATE exchange_requests SET owner=?,claim_until=DATE_ADD(NOW(6),INTERVAL 30 SECOND),attempts=attempts+1 WHERE id=? AND phase IN ('RESERVING','CONFIRMING','CANCELLING') AND (owner IS NULL OR claim_until<NOW(6))",owner,id)!=1) return;
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        try {
            var r=db.queryForMap("SELECT * FROM exchange_requests WHERE id=?",id);
            long student=((Number)r.get("student_id")).longValue(),course=((Number)r.get("course_id")).longValue();
            String phase=(String)r.get("phase");
            if(phase.equals("RESERVING")) {
                try(var permit=admission.enter(course,deadline); var lease=gate.acquire(course,deadline)) {
                    String state=partner.status(id,student,course,deadline);
                    if(state.equals("ABSENT")) state=partner.reserve(id,student,course,deadline);
                    if(state.equals("FULL") || state.equals("CANCELLED")) {
                        change(id,owner,"RESERVING","CANCELLED","PARTNER_"+state); return;
                    }
                    if(!state.equals("HELD")) throw RedisGate.failure("PARTNER_STATE_MISMATCH");
                    lease.check();
                    try {
                        // Saga phase and local enrollment commit together. A lost commit response is recoverable.
                        enrollment.commitExchange(student,course,"exchange:"+id,deadline,lease,id,owner);
                    } catch(EnrollmentException error) {
                        if(!error.getStatus().is4xxClientError()) throw error;
                        change(id,owner,"RESERVING","CANCELLING",error.getCode());
                    } catch(org.springframework.dao.DuplicateKeyException error) {
                        change(id,owner,"RESERVING","CANCELLING","DUPLICATE_ENROLLMENT");
                    }
                }
                phase=get(id).phase();
            }
            if(phase.equals("CONFIRMING")) {
                String state=partner.status(id,student,course,deadline);
                if(state.equals("HELD")) state=partner.confirm(id,student,course,deadline);
                if(!state.equals("CONFIRMED")) throw RedisGate.failure("PARTNER_STATE_MISMATCH");
                change(id,owner,"CONFIRMING","COMPLETED",null);
            } else if(phase.equals("CANCELLING")) {
                String state=partner.cancel(id,student,course,deadline);
                if(!state.equals("CANCELLED")) throw RedisGate.failure("PARTNER_STATE_MISMATCH");
                db.update("UPDATE exchange_requests SET phase='CANCELLED' WHERE id=? AND owner=? AND phase='CANCELLING'",id,owner);
            }
        } catch(RuntimeException error) {
            String code=error instanceof EnrollmentException e?e.getCode():error.getClass().getSimpleName();
            db.update("UPDATE exchange_requests SET last_error=? WHERE id=? AND owner=? AND phase<>'CANCELLING'",code,id,owner);
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("event=exchange_retry request={} cause={}",id,code);
        } finally {
            db.update("UPDATE exchange_requests SET owner=NULL,claim_until=NULL,next_attempt=DATE_ADD(NOW(6),INTERVAL 2 SECOND) WHERE id=? AND owner=?",id,owner);
        }
    }
    private void change(String id,String owner,String from,String to,String error) {
        db.update("UPDATE exchange_requests SET phase=?,last_error=? WHERE id=? AND owner=? AND phase=?",to,error,id,owner,from);
    }
}
