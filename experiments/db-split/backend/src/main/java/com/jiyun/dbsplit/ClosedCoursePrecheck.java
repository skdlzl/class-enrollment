package com.jiyun.dbsplit;

import java.time.Duration;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Advisory only: stale Redis data must never be the sole basis for rejecting a request. */
@Component
public class ClosedCoursePrecheck {
    private final boolean enabled;
    private final RedissonClient redis;
    private final JdbcTemplate jdbc;
    private final Metrics metrics;
    public ClosedCoursePrecheck(@Value("${experiment.closed-precheck:false}") boolean enabled,
            ObjectProvider<RedissonClient> clients, JdbcTemplate jdbc, Metrics metrics) {
        this.enabled=enabled; this.redis=clients.getIfAvailable(); this.jdbc=jdbc; this.metrics=metrics;
    }
    String key(EnrollmentService.Request r) {
        return "enrollment:closed:"+r.runId()+":"+r.termId()+":"+r.courseId();
    }
    private boolean full(EnrollmentService.Request r) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM offered_course WHERE run_id=? AND term_id=? AND id=? AND enrolled_count>=capacity)",
            Boolean.class,r.runId(),r.termId(),r.courseId()));
    }
    public void check(EnrollmentService.Request r) {
        if(!enabled || redis==null)return;
        boolean hint;
        try { hint=redis.getBucket(key(r),StringCodec.INSTANCE).isExists(); }
        catch(RuntimeException e){ metrics.record("closedPrecheck.redisFailure",0,true);return; }
        if(!hint)return;
        // One SQL snapshot avoids reading "unknown request" before a concurrent commit
        // and "full course" after that commit for the very same request.
        boolean reject=Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT NOT EXISTS(SELECT 1 FROM enrollment WHERE request_id=?) AND EXISTS(SELECT 1 FROM offered_course WHERE run_id=? AND term_id=? AND id=? AND enrolled_count>=capacity)",
            Boolean.class,r.requestId(),r.runId(),r.termId(),r.courseId()));
        if(reject){
            metrics.record("closedPrecheck.rejected",0,false);
            throw new EnrollmentService.Rejected("COURSE_FULL");
        }
        clear(r); // Cancellation, capacity increase or an out-of-order hint write.
    }
    public void refresh(EnrollmentService.Request r) {
        if(!enabled || redis==null)return;
        try {
            if(full(r))redis.getBucket(key(r),StringCodec.INSTANCE).set("1",Duration.ofSeconds(5));
            else clear(r);
        }catch(RuntimeException e){
            // An optional hint update must never replace an already committed enrollment result.
            metrics.record("closedPrecheck.refreshFailure",0,true);
        }
    }
    public void clear(EnrollmentService.Request r) {
        if(!enabled || redis==null)return;
        try { redis.getBucket(key(r),StringCodec.INSTANCE).delete(); }
        catch(RuntimeException e){metrics.record("closedPrecheck.redisFailure",0,true);}
    }
}
