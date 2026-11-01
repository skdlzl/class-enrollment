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
    private final JdbcTemplate db;
    private final RedisGate gate;
    private final PartnerClient partner;
    private final EnrollmentCoordinator enrollment;
    private final long responseWaitMs;
    public ExchangeService(JdbcTemplate db,RedisGate gate,PartnerClient partner,EnrollmentCoordinator enrollment) {
        this(db,gate,partner,enrollment,0);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public ExchangeService(JdbcTemplate db,RedisGate gate,PartnerClient partner,EnrollmentCoordinator enrollment,
            @org.springframework.beans.factory.annotation.Value("${enrollment.response-wait-ms:750}") long responseWaitMs) {
        this.db=db; this.gate=gate; this.partner=partner; this.enrollment=enrollment;
        this.responseWaitMs=Math.max(0,Math.min(1000,responseWaitMs));
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
        // Bounded response coalescing; no Redis lock or DB connection is retained between reads.
        long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(responseWaitMs);
        View view=get(id);
        while(!Set.of("COMPLETED","CANCELLED").contains(view.phase()) && view.lastError()==null && System.nanoTime()<until) {
            try { Thread.sleep(Math.min(100,Math.max(1,TimeUnit.NANOSECONDS.toMillis(until-System.nanoTime())))); }
            catch(InterruptedException e) { Thread.currentThread().interrupt(); break; }
            view=get(id);
        }
        return view;
    }
    public View get(String id) {
        var rows=db.queryForList("SELECT * FROM exchange_requests WHERE id=?",id);
        if(rows.isEmpty()) throw new EnrollmentException("REQUEST_NOT_FOUND","신청이 없습니다.",HttpStatus.NOT_FOUND);
        var r=rows.get(0);
        return new View(id,(String)r.get("phase"),r.get("enrollment_id")==null?null:((Number)r.get("enrollment_id")).longValue(),(String)r.get("last_error"));
    }
    public void recover() { closeUntouchedFull(); for(String id:recoveryCandidates()) process(id); }
    public void closeUntouchedFull() {
        // Atomic terminalization is safe only when no external call has ever been authorized.
        var closed=db.queryForList("SELECT id FROM courses WHERE enrolled_count>=capacity",Long.class);
        if(closed.isEmpty()) return;
        String placeholders=String.join(",",Collections.nCopies(closed.size(),"?"));
        db.update("UPDATE exchange_requests SET phase='CANCELLED',last_error='COURSE_FULL' "
            +"WHERE phase='RESERVING' AND remote_attempts=0 AND (owner IS NULL OR claim_until<NOW(6)) "
            +"AND course_id IN ("+placeholders+")",closed.toArray());
    }
    public List<String> recoveryCandidates() {
        var result=new ArrayList<>(db.queryForList("""
            SELECT id FROM exchange_requests WHERE phase IN ('CONFIRMING','CANCELLING')
            AND next_attempt<=NOW(6) AND (owner IS NULL OR claim_until<NOW(6))
            ORDER BY next_attempt LIMIT 32
            """,String.class));
        if(enrollment.strategy()!=EnrollmentCoordinator.Strategy.REDIS) {
            result.addAll(db.queryForList("SELECT id FROM exchange_requests WHERE phase='RESERVING' AND next_attempt<=NOW(6) AND (owner IS NULL OR claim_until<NOW(6)) ORDER BY next_attempt LIMIT 32",String.class));
        } else {
            // Include owned heads in ranking: never skip a head merely because it is being processed.
            result.addAll(db.queryForList("""
                SELECT r.id FROM (SELECT DISTINCT course_id FROM exchange_requests WHERE phase='RESERVING') c
                JOIN exchange_requests r ON r.id=(
                    SELECT q.id FROM exchange_requests q WHERE q.course_id=c.course_id AND q.phase='RESERVING'
                    ORDER BY q.remote_attempts DESC,q.created_at,q.id LIMIT 1
                ) WHERE r.next_attempt<=NOW(6) AND (r.owner IS NULL OR r.claim_until<NOW(6)) LIMIT 64
                """,String.class));
        }
        return result;
    }
    private boolean isHead(String id,long course) {
        var heads=db.queryForList("SELECT id FROM exchange_requests WHERE course_id=? AND phase='RESERVING' ORDER BY remote_attempts DESC,created_at,id LIMIT 1",String.class,course);
        return !heads.isEmpty() && heads.get(0).equals(id);
    }
    public void process(String id) {
        String owner=UUID.randomUUID().toString();
        if(db.update("UPDATE exchange_requests SET owner=?,claim_until=DATE_ADD(NOW(6),INTERVAL 30 SECOND),attempts=attempts+1 WHERE id=? AND phase IN ('RESERVING','CONFIRMING','CANCELLING') AND (owner IS NULL OR claim_until<NOW(6))",owner,id)!=1) return;
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        boolean queued=false;
        long retryDelayMicros=2000000;
        try {
            var r=db.queryForMap("SELECT * FROM exchange_requests WHERE id=?",id);
            long student=((Number)r.get("student_id")).longValue(),course=((Number)r.get("course_id")).longValue();
            String phase=(String)r.get("phase");
            String previousError=(String)r.get("last_error");
            boolean previouslyAttempted=((Number)r.get("remote_attempts")).longValue()>0;
            if(phase.equals("RESERVING")) {
                try {
                    // Identical committed-full fast path for all three strategies.
                    rejectFull(course);
                    if(enrollment.strategy()==EnrollmentCoordinator.Strategy.REDIS) {
                        if(!isHead(id,course)) { queued=true; enrollment.exchangeQueued("TURN"); return; }
                        long lockStart=System.nanoTime();
                        RedisGate.Lease acquired;
                        try { acquired=gate.tryAcquire(course); }
                        finally { enrollment.exchangeStage("lock_wait",lockStart); }
                        if(acquired==null) { queued=true; enrollment.exchangeQueued("LOCK_BUSY"); return; }
                        try(var lease=acquired) {
                            if(!isHead(id,course)) { queued=true; enrollment.exchangeQueued("TURN"); return; }
                            rejectFull(course);
                            reserve(id,student,course,deadline,owner,previouslyAttempted);
                            lease.check();
                            enrollment.commitExchange(student,course,"exchange:"+id,deadline,lease,id,owner);
                        }
                    } else {
                        // Pessimistic: course FOR UPDATE; optimistic: snapshot then version CAS.
                        // The same idempotent partner operation executes inside the DB transaction.
                        // This marker MUST survive a rollback of the transaction containing HTTP.
                        authorizeRemote(id,owner);
                        enrollment.commitExchange(student,course,"exchange:"+id,deadline,id,owner,
                            ()->reserve(id,student,course,deadline,owner,previouslyAttempted));
                    }
                } catch(EnrollmentException error) {
                    if(!error.getStatus().is4xxClientError()) throw error;
                    reject(id,owner,error.getCode());
                } catch(org.springframework.dao.DuplicateKeyException error) {
                    reject(id,owner,"DUPLICATE_ENROLLMENT");
                }
                var current=get(id);
                phase=current.phase(); previousError=current.lastError();
            }
            if(phase.equals("CONFIRMING")) {
                String state=previousError==null?"HELD":partner.status(id,student,course,deadline);
                if(state.equals("HELD")) state=partner.confirm(id,student,course,deadline);
                if(!state.equals("CONFIRMED")) throw RedisGate.failure("PARTNER_STATE_MISMATCH");
                change(id,owner,"CONFIRMING","COMPLETED",null);
            } else if(phase.equals("CANCELLING")) {
                String state=partner.cancel(id,student,course,deadline);
                if(!state.equals("CANCELLED")) throw RedisGate.failure("PARTNER_STATE_MISMATCH");
                db.update("UPDATE exchange_requests SET phase='CANCELLED' WHERE id=? AND owner=? AND phase='CANCELLING'",id,owner);
            }
        } catch(RuntimeException error) {
            String code=error instanceof EnrollmentException e?e.getCode():EnrollmentCoordinator.retryCause(error);
            enrollment.exchangeFailure(code);
            if(Set.of("DB_DEADLOCK","DB_LOCK_WAIT_TIMEOUT","VERSION_CONFLICT").contains(code))
                retryDelayMicros=java.util.concurrent.ThreadLocalRandom.current().nextLong(25000,100001);
            else if(code.startsWith("PARTNER_")) retryDelayMicros=250000;
            db.update("UPDATE exchange_requests SET last_error=? WHERE id=? AND owner=? AND phase IN ('RESERVING','CONFIRMING')",code,id,owner);
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("event=exchange_retry request={} cause={}",id,code);
        } finally {
            db.update("UPDATE exchange_requests SET owner=NULL,claim_until=NULL,next_attempt=TIMESTAMPADD(MICROSECOND,?,NOW(6)) WHERE id=? AND owner=?",queued?100000:retryDelayMicros,id,owner);
        }
    }
    private void rejectFull(long course) {
        if(db.queryForObject("SELECT COUNT(*) FROM courses WHERE id=? AND enrolled_count>=capacity",Long.class,course)>0)
            throw new EnrollmentException("COURSE_FULL","COURSE_FULL",HttpStatus.CONFLICT);
    }
    private void reject(String id,String owner,String code) {
        // Increment-before-HTTP and this conditional update serialize on the request row.
        // An old worker cannot send a reserve after this request becomes terminal.
        db.update("""
            UPDATE exchange_requests SET phase=CASE WHEN remote_attempts=0 THEN 'CANCELLED' ELSE 'CANCELLING' END,
            last_error=? WHERE id=? AND owner=? AND phase='RESERVING'
            """,code,id,owner);
    }
    private void authorizeRemote(String id,String owner) {
        if(db.update("UPDATE exchange_requests SET remote_attempts=remote_attempts+1 WHERE id=? AND owner=? AND phase='RESERVING' AND claim_until>NOW(6)",id,owner)!=1)
            throw RedisGate.failure("EXCHANGE_CLAIM_LOST");
    }
    private void reserve(String id,long student,long course,long deadline,String owner,boolean previouslyAttempted) {
        authorizeRemote(id,owner);
        String state=previouslyAttempted?partner.status(id,student,course,deadline):"ABSENT";
        if(state.equals("ABSENT")) state=partner.reserve(id,student,course,deadline);
        if(state.equals("FULL") || state.equals("CANCELLED"))
            throw new EnrollmentException("PARTNER_"+state,"PARTNER_"+state,HttpStatus.CONFLICT);
        if(!state.equals("HELD")) throw RedisGate.failure("PARTNER_STATE_MISMATCH");
    }
    private void change(String id,String owner,String from,String to,String error) {
        db.update("UPDATE exchange_requests SET phase=?,last_error=? WHERE id=? AND owner=? AND phase=?",to,error,id,owner,from);
    }
}
