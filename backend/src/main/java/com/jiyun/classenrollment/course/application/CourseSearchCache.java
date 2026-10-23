package com.jiyun.classenrollment.course.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiyun.classenrollment.course.api.CourseQueryController.Page;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/** Public search snapshots only. Never used to authorize an enrollment write. */
@Component
public class CourseSearchCache {
    private final RedissonClient redis;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final long ttlMs;
    private final Object[] stripes = new Object[128];
    private final AtomicLong bypassUntil = new AtomicLong();
    private final LongAdder requests=new LongAdder(), hits=new LongAdder(), loads=new LongAdder(),
        errors=new LongAdder(), bypasses=new LongAdder();
    public record Entry(long expiresAt, Page page) { }
    public CourseSearchCache(RedissonClient redis, ObjectMapper mapper,
            @Value("${enrollment.search-cache.enabled:false}") boolean enabled,
            @Value("${enrollment.search-cache.ttl-ms:1000}") long ttlMs) {
        if(ttlMs<1 || ttlMs>5000) throw new IllegalArgumentException("Search cache TTL must be 1..5000ms");
        this.redis=redis;this.mapper=mapper;this.enabled=enabled;this.ttlMs=ttlMs;
        for(int i=0;i<stripes.length;i++) stripes[i]=new Object();
    }
    public Page get(String queryKey, Supplier<Page> loader) {
        requests.increment();
        if(!enabled || bypassUntil.get()>System.currentTimeMillis()) {
            bypasses.increment();return load(loader);
        }
        RBucket<String> bucket=redis.getBucket(key(queryKey), StringCodec.INSTANCE);
        Page cached=read(bucket);
        if(cached!=null){hits.increment();return cached;}
        if(bypassUntil.get()>System.currentTimeMillis())return load(loader);
        // Coalesce local misses without borrowing a DB connection while waiting.
        // At most one loader per stripe per application; servers share Redis entries.
        synchronized(stripes[Math.floorMod(queryKey.hashCode(),stripes.length)]) {
            if(bypassUntil.get()>System.currentTimeMillis())return load(loader);
            cached=read(bucket);
            if(cached!=null){hits.increment();return cached;}
            long expiresAt=System.currentTimeMillis()+ttlMs;
            Page page=load(loader); // DB failures propagate; never cache failed queries.
            long remaining=expiresAt-System.currentTimeMillis();
            if(remaining>0 && bypassUntil.get()<=System.currentTimeMillis()) {
                try {
                    bucket.setAsync(mapper.writeValueAsString(new Entry(expiresAt,page)),Duration.ofMillis(remaining))
                        .toCompletableFuture().get(100,TimeUnit.MILLISECONDS);
                } catch(InterruptedException e) {Thread.currentThread().interrupt();failed();}
                  catch(Exception e) {failed();}
            }
            return page;
        }
    }
    private Page load(Supplier<Page> loader){loads.increment();return loader.get();}
    private Page read(RBucket<String> bucket) {
        try {
            String json=bucket.getAsync().toCompletableFuture().get(100,TimeUnit.MILLISECONDS);
            if(json==null)return null;
            Entry entry=mapper.readValue(json,Entry.class);
            // Also reject expired payloads if a delayed SET arrives after its intended deadline.
            return entry.expiresAt()>System.currentTimeMillis()?entry.page():null;
        } catch(InterruptedException e) {Thread.currentThread().interrupt();failed();return null;}
          catch(Exception e) {failed();return null;}
    }
    private void failed(){errors.increment();bypassUntil.set(System.currentTimeMillis()+5000);}
    public static String key(String queryKey) {
        try {return "enrollment:search:v1:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(queryKey.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    public Map<String,Object> snapshot(){
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("enabled",enabled);result.put("ttlMs",ttlMs);result.put("requests",requests.sum());
        result.put("hits",hits.sum());result.put("dbLoads",loads.sum());result.put("errors",errors.sum());
        result.put("bypasses",bypasses.sum());return result;
    }
}
