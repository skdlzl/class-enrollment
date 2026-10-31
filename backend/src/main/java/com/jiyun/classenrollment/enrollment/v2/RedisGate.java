package com.jiyun.classenrollment.enrollment.v2;

import com.jiyun.classenrollment.common.error.EnrollmentException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class RedisGate {
    private final StringRedisTemplate redis;
    private final long leaseMs;
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
        "if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end", Long.class);
    private static final DefaultRedisScript<Long> CHECK = new DefaultRedisScript<>(
        "if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('pttl',KEYS[1]) else return -1 end", Long.class);

    public RedisGate(StringRedisTemplate redis, @Value("${enrollment.lease-ms:3000}") long leaseMs) {
        this.redis = redis;
        this.leaseMs = leaseMs;
    }
    public Lease acquire(long course, long deadline) {
        String key = "enrollment:course:" + course;
        String owner = UUID.randomUUID().toString();
        try {
            while (System.nanoTime() < deadline) {
                if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, owner, Duration.ofMillis(leaseMs))))
                    return new Lease(key, owner);
                Thread.sleep(ThreadLocalRandom.current().nextLong(5, 16));
            }
            throw failure("REDIS_LOCK_TIMEOUT");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure("INTERRUPTED");
        } catch (org.springframework.dao.DataAccessException e) {
            throw failure("REDIS_UNAVAILABLE");
        }
    }
    public final class Lease implements AutoCloseable {
        private final String key, owner;
        Lease(String key, String owner) { this.key = key; this.owner = owner; }
        public void check() {
            try {
                Long remaining = redis.execute(CHECK, List.of(key), owner);
                if (remaining == null || remaining <= 0) throw failure("LEASE_LOST");
            } catch (org.springframework.dao.DataAccessException e) { throw failure("REDIS_UNAVAILABLE"); }
        }
        public void close() {
            // A failed unlock cannot turn a committed enrollment into an apparent failure.
            try { redis.execute(RELEASE, List.of(key), owner); }
            catch (org.springframework.dao.DataAccessException e) {
                org.slf4j.LoggerFactory.getLogger(RedisGate.class).warn("event=unlock_failed key={} expiry_ms={}", key, leaseMs);
            }
        }
    }
    public static EnrollmentException failure(String code) {
        return new EnrollmentException(code, code, HttpStatus.SERVICE_UNAVAILABLE);
    }
}
