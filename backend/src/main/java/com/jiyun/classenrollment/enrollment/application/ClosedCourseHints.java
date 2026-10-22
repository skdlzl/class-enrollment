package com.jiyun.classenrollment.enrollment.application;

import com.jiyun.classenrollment.common.error.EnrollmentException;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Shared negative hint, never an authoritative seat reservation or rejection. */
@Component
public class ClosedCourseHints {
    private final RedissonClient redis;
    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final Duration ttl;
    private final AtomicLong checks=new AtomicLong(),hits=new AtomicLong(),rejected=new AtomicLong(),
            stale=new AtomicLong(),marks=new AtomicLong(),redisErrors=new AtomicLong(),dbErrors=new AtomicLong();

    public ClosedCourseHints(RedissonClient redis,JdbcTemplate jdbc,
            @Value("${enrollment.closed-course-hint.enabled:false}") boolean enabled,
            @Value("${enrollment.closed-course-hint.ttl-ms:5000}") long ttlMs) {
        if(ttlMs<1)throw new IllegalArgumentException("Closed-course hint TTL must be positive");
        this.redis=redis;this.jdbc=jdbc;this.enabled=enabled;this.ttl=Duration.ofMillis(ttlMs);
    }
    private org.redisson.api.RBucket<String> bucket(Long courseId) {
        return redis.getBucket("enrollment:closed-hint:v1:"+courseId,StringCodec.INSTANCE);
    }
    public void rejectIfClosed(Long courseId) {
        if(!enabled)return;
        checks.incrementAndGet();
        try {if(!bucket(courseId).isExists())return;}
        catch(RedisException e){redisErrors.incrementAndGet();return;}
        hits.incrementAndGet();
        // Autocommit scalar query on the primary, before the enrollment transaction.
        // A stale flag MUST NOT reject a reopened course. No SELECT FOR UPDATE here.
        final java.util.List<Boolean> rows;
        try {rows=jdbc.queryForList("SELECT enrolled_count>=capacity FROM courses WHERE id=?",Boolean.class,courseId);}
        catch(DataAccessException e){dbErrors.incrementAndGet();return;}
        if(!rows.isEmpty()&&Boolean.TRUE.equals(rows.get(0))) {
            rejected.incrementAndGet();
            throw new EnrollmentException("COURSE_FULL","수강 정원이 마감되었습니다.",HttpStatus.CONFLICT);
        }
        stale.incrementAndGet();clear(courseId);
    }
    public void mark(Long courseId) {
        if(!enabled)return;
        try {bucket(courseId).set("closed",ttl);marks.incrementAndGet();}
        catch(RedisException e){redisErrors.incrementAndGet();}
    }
    public void markAfterCommit(Long courseId) {
        if(!enabled||!TransactionSynchronizationManager.isActualTransactionActive()
                ||!TransactionSynchronizationManager.isSynchronizationActive())return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void afterCommit(){mark(courseId);}
        });
    }
    /** Future cancellation/capacity-update paths may invalidate after commit.
     * Delayed mark racing with clear is safe because every positive hit checks the DB.
     */
    public void clear(Long courseId) {
        if(!enabled)return;
        try {bucket(courseId).delete();}catch(RedisException e){redisErrors.incrementAndGet();}
    }
    public Map<String,Object> snapshot() {
        return Map.of("enabled",enabled,"ttlMs",ttl.toMillis(),"checks",checks.get(),"hits",hits.get(),
                "rejected",rejected.get(),"stale",stale.get(),"marks",marks.get(),
                "redisErrors",redisErrors.get(),"dbErrors",dbErrors.get());
    }
}
