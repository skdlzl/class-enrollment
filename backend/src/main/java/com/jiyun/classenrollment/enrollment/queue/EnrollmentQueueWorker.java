package com.jiyun.classenrollment.enrollment.queue;

import com.jiyun.classenrollment.common.error.EnrollmentException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.redisson.api.*;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

@Component
@ConditionalOnProperty(name="enrollment.queue.enabled", havingValue="true")
public class EnrollmentQueueWorker {
    private static final Logger log=LoggerFactory.getLogger(EnrollmentQueueWorker.class);
    // v2 scores are Redis-clock eligibility times, not receipt sequence numbers.
    private static final String READY="enrollment:ready:v2";
    private static final String ENQUEUE="""
        local t=redis.call('TIME')
        local due=tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)+tonumber(ARGV[2])
        return redis.call('ZADD',KEYS[1],'NX',due,ARGV[1])
        """;
    private static final String POP_DUE="""
        local t=redis.call('TIME')
        local now=tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)
        local ids=redis.call('ZRANGEBYSCORE',KEYS[1],'-inf',now,'LIMIT',0,1)
        if #ids==0 then return nil end
        redis.call('ZREM',KEYS[1],ids[1])
        return ids[1]
        """;
    private final EnrollmentRequestStore store;
    private final QueuedEnrollmentTransaction transaction;
    private final RedissonClient redis;
    private final int workers, slots, maxAttempts;
    private final LongAdder handled=new LongAdder(), deferred=new LongAdder(), fastRejected=new LongAdder();
    private ScheduledExecutorService executor;
    private volatile boolean running;
    public EnrollmentQueueWorker(EnrollmentRequestStore store, QueuedEnrollmentTransaction transaction,
            RedissonClient redis, @Value("${enrollment.queue.workers:2}") int workers,
            @Value("${enrollment.queue.global-slots:4}") int slots,
            @Value("${enrollment.queue.max-attempts:5}") int maxAttempts) {
        if(workers<1 || workers>16 || slots<1 || slots>32 || maxAttempts<1) throw new IllegalArgumentException("Invalid queue limits");
        this.store=store; this.transaction=transaction; this.redis=redis;
        this.workers=workers; this.slots=slots; this.maxAttempts=maxAttempts;
    }
    @Value("${enrollment.queue.scheduler-enabled:true}")
    private boolean schedulerEnabled=true;
    @PostConstruct
    public void start() {
        if(!schedulerEnabled) return;
        running=true;
        executor=Executors.newScheduledThreadPool(workers+1);
        executor.scheduleWithFixedDelay(this::recover,0,5,TimeUnit.SECONDS);
        for(int i=0;i<workers;i++) executor.execute(this::runWorker);
    }
    private void runWorker() {
        while(running && !Thread.currentThread().isInterrupted()) {
            // No fixed delay after completed work. Only back off when no due work/slot is available.
            if(!drainBatch()) {
                try {Thread.sleep(50);} catch(InterruptedException e){Thread.currentThread().interrupt();return;}
            }
        }
    }
    public void publish(EnrollmentTicket ticket) {
        if(ticket.terminal()) return;
        try {
            redis.getScript(StringCodec.INSTANCE).evalAsync(RScript.Mode.READ_WRITE,ENQUEUE,
                RScript.ReturnType.LONG,List.of(READY),ticket.requestId(),0).whenComplete((added,error)->{
                    if(error!=null) log.warn("QUEUE_PUBLISH_DEFERRED requestId={}",ticket.requestId());
                });
        } catch(Exception error) {log.warn("QUEUE_PUBLISH_DEFERRED requestId={}",ticket.requestId());}
    }
    private void enqueue(String id, long delayMs) {
        redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE,ENQUEUE,
            RScript.ReturnType.LONG,List.of(READY),id,delayMs);
    }
    private void defer(String id) {
        deferred.increment();
        enqueue(id,ThreadLocalRandom.current().nextLong(50,151));
    }
    void recover() {
        try {
            // NX preserves a short retry deadline already in Redis.
            for(var ticket:store.recoverable()) enqueue(ticket.requestId(),0);
            log.info("QUEUE_PROGRESS handled={} lockDeferred={} fastRejected={}",
                handled.sum(),deferred.sum(),fastRejected.sum());
        } catch(Exception error) { log.warn("QUEUE_RECOVERY_FAILED",error); }
    }
    public void tick() { drainBatch(); }
    private boolean drainBatch() {
        RLock slot=null;
        boolean consumed=false;
        try {
            for(int i=0;i<slots;i++) {
                RLock candidate=redis.getLock("enrollment:worker-slot:"+i);
                if(candidate.tryLock()) {slot=candidate; break;}
            }
            if(slot==null) return false;
            // Release the global slot periodically so another server can get a turn.
            long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(100);
            for(int i=0;i<32;i++) {
                String id=redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE,POP_DUE,
                    RScript.ReturnType.VALUE,List.of(READY));
                if(id==null) break;
                consumed=true;
                process(id);
                if(System.nanoTime()>=until) break;
            }
            return consumed;
        } catch(Exception error) { log.warn("QUEUE_WORKER_FAILED",error);return false; }
        finally { release(slot); }
    }
    void process(String id) {
        RLock job=redis.getLock("enrollment:request:"+id),student=null,course=null;
        if(!job.tryLock()) {defer(id);return;}
        boolean started=false;
        try {
            var ticket=store.get(id);
            if(ticket.terminal()) return;
            // Rejection optimization only; existing enrollments must still reconcile as CONFIRMED.
            if(store.isFullWithoutEnrollment(ticket.studentId(),ticket.courseId())) {
                started=store.start(id);
                if(started) {
                    store.reject(id,"수강 정원이 마감되었습니다.");
                    fastRejected.increment();handled.increment();
                }
                return;
            }
            student=redis.getLock("lock:student:"+ticket.studentId());
            if(!student.tryLock()) {student=null;defer(id);return;}
            course=redis.getLock("lock:course:"+ticket.courseId());
            if(!course.tryLock()) {course=null;defer(id);return;}
            started=store.start(id);
            if(!started) return;
            transaction.confirm(ticket);
            handled.increment();
        } catch(EnrollmentException error) {
            if(started) {
                if(error.getStatus().is4xxClientError()) {store.reject(id,error.getMessage());handled.increment();}
                else store.retryLater(id,maxAttempts);
            }
        } catch(Exception error) {
            log.warn("QUEUE_PROCESSING_FAILED requestId={}",id,error);
            if(started) store.retryLater(id,maxAttempts);
        } finally { release(course); release(student); release(job); }
    }
    private void release(RLock lock) {
        if(lock==null) return;
        try { if(lock.isHeldByCurrentThread()) lock.unlock(); }
        catch(Exception error) {log.warn("QUEUE_UNLOCK_FAILED",error);}
    }
    @PreDestroy
    public void stop() {
        running=false;
        if(executor!=null) {
            executor.shutdown();
            try {if(!executor.awaitTermination(15,TimeUnit.SECONDS)) executor.shutdownNow();}
            catch(InterruptedException e) {executor.shutdownNow(); Thread.currentThread().interrupt();}
        }
    }
}
