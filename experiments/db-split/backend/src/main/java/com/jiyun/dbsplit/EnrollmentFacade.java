package com.jiyun.dbsplit;
import org.redisson.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

@Service
public class EnrollmentFacade {
    public static class Unavailable extends RuntimeException {
        public final String code;
        Unavailable(String code,Throwable cause){super(code,cause);this.code=code;}
    }
    private final EnrollmentService service;
    private final Metrics metrics;
    private final RedissonClient redis;
    private final AtomicInteger inFlight=new AtomicInteger();
    public EnrollmentFacade(EnrollmentService service,Metrics metrics,ObjectProvider<RedissonClient> clients){
        this.service=service;this.metrics=metrics;this.redis=clients.getIfAvailable();
    }
    public int inFlight(){return inFlight.get();}
    public EnrollmentService.Result enroll(EnrollmentService.Request r){
        return execute(r,()->metrics.time("enrollment.transactionProxy",()->service.enroll(r)));
    }
    public EnrollmentService.Result cancel(EnrollmentService.Request r){
        return execute(r,()->metrics.time("cancellation.transactionProxy",()->service.cancel(r)));
    }
    String studentKey(EnrollmentService.Request r){return "db-split:"+r.runId()+":student:"+r.termId()+":"+r.studentId();}
    String courseKey(EnrollmentService.Request r){return "db-split:"+r.runId()+":course:"+r.termId()+":"+r.courseId();}
    private <T>T execute(EnrollmentService.Request r,Supplier<T> action){
        inFlight.incrementAndGet();
        try{
            if(redis==null)return action.get();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            return locked(studentKey(r),"studentRedis",deadline,
                ()->locked(courseKey(r),"courseRedis",deadline,action));
        }finally{inFlight.decrementAndGet();}
    }
    private <T>T locked(String key,String phase,long deadline,Supplier<T> action){
        RLock lock=redis.getLock(key);
        long start=System.nanoTime();boolean acquired=false;
        try{
            long remaining=Math.max(0,deadline-System.nanoTime());
            acquired=lock.tryLock(remaining,TimeUnit.NANOSECONDS);
            if(!acquired)throw new Unavailable("REDIS_LOCK_TIMEOUT",null);
        }catch(InterruptedException e){
            Thread.currentThread().interrupt();throw new Unavailable("REDIS_LOCK_INTERRUPTED",e);
        }catch(Unavailable e){throw e;
        }catch(RuntimeException e){throw new Unavailable("REDIS_UNAVAILABLE",e);
        }finally{metrics.record(phase+".wait",System.nanoTime()-start,!acquired);}
        long held=System.nanoTime();
        try{return action.get();} // Transactional proxy returns after commit/rollback.
        finally{
            metrics.record(phase+".holdUntilCompletion",System.nanoTime()-held,false);
            try{lock.unlock();}
            catch(RuntimeException e){
                // Preserve committed results even if the lease was lost. DB guards remain authoritative.
                metrics.record(phase+".unlockFailure",0,true);
            }
        }
    }
}
