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
import java.util.concurrent.*;

@Component
@ConditionalOnProperty(name="enrollment.queue.enabled", havingValue="true")
public class EnrollmentQueueWorker {
    private static final Logger log=LoggerFactory.getLogger(EnrollmentQueueWorker.class);
    private final EnrollmentRequestStore store;
    private final QueuedEnrollmentTransaction transaction;
    private final RedissonClient redis;
    private final RScoredSortedSet<String> ready;
    private final int workers, slots, maxAttempts;
    private ScheduledExecutorService executor;
    public EnrollmentQueueWorker(EnrollmentRequestStore store, QueuedEnrollmentTransaction transaction,
            RedissonClient redis, @Value("${enrollment.queue.workers:2}") int workers,
            @Value("${enrollment.queue.global-slots:4}") int slots,
            @Value("${enrollment.queue.max-attempts:5}") int maxAttempts) {
        if(workers<1 || workers>16 || slots<1 || slots>32 || maxAttempts<1) throw new IllegalArgumentException("Invalid queue limits");
        this.store=store; this.transaction=transaction; this.redis=redis;
        this.workers=workers; this.slots=slots; this.maxAttempts=maxAttempts;
        ready=redis.getScoredSortedSet("enrollment:ready:v1",StringCodec.INSTANCE);
    }
    @Value("${enrollment.queue.scheduler-enabled:true}")
    private boolean schedulerEnabled=true;
    @PostConstruct
    public void start() {
        if(!schedulerEnabled) return;
        executor=Executors.newScheduledThreadPool(workers+1);
        executor.scheduleWithFixedDelay(this::recover,0,5,TimeUnit.SECONDS);
        for(int i=0;i<workers;i++) executor.scheduleWithFixedDelay(this::tick,250,250,TimeUnit.MILLISECONDS);
    }
    public void publish(EnrollmentTicket ticket) {
        if(ticket.terminal()) return;
        try {
            ready.addAsync(ticket.id(),ticket.requestId()).whenComplete((added,error)->{
                if(error!=null) log.warn("QUEUE_PUBLISH_DEFERRED requestId={}",ticket.requestId());
            });
        } catch(Exception error) {log.warn("QUEUE_PUBLISH_DEFERRED requestId={}",ticket.requestId());}
    }
    // DB is the durable source. A crash between DB insert and publish cannot lose a receipt.
    void recover() {
        try { for(var ticket:store.recoverable()) ready.add(ticket.id(),ticket.requestId()); }
        catch(Exception error) { log.warn("QUEUE_RECOVERY_FAILED",error); }
    }
    public void tick() {
        RLock slot=null;
        try {
            for(int i=0;i<slots;i++) {
                RLock candidate=redis.getLock("enrollment:worker-slot:"+i);
                if(candidate.tryLock()) {slot=candidate; break;}
            }
            if(slot==null) return;
            String id=ready.pollFirst();
            if(id!=null) process(id);
        } catch(Exception error) { log.warn("QUEUE_WORKER_FAILED",error); }
        finally { release(slot); }
    }
    void process(String id) {
        RLock job=redis.getLock("enrollment:request:"+id),student=null,course=null;
        if(!job.tryLock()) return; // Recovered by the durable scan if another worker crashes.
        boolean started=false;
        try {
            var ticket=store.get(id);
            if(ticket.terminal()) return;
            student=redis.getLock("lock:student:"+ticket.studentId());
            if(!student.tryLock()) {student=null; return;}
            course=redis.getLock("lock:course:"+ticket.courseId());
            if(!course.tryLock()) {course=null; return;}
            started=store.start(id);
            if(!started) return;
            transaction.confirm(ticket);
        } catch(EnrollmentException error) {
            if(started) {
                if(error.getStatus().is4xxClientError()) store.reject(id,error.getMessage());
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
        if(executor!=null) {
            executor.shutdown();
            try {if(!executor.awaitTermination(15,TimeUnit.SECONDS)) executor.shutdownNow();}
            catch(InterruptedException e) {executor.shutdownNow(); Thread.currentThread().interrupt();}
        }
    }
}
