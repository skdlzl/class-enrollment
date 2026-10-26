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
    private final DatabaseConfig.Mode mode;
    private final RedissonClient redis;
    private final AtomicInteger inFlight=new AtomicInteger();
    public EnrollmentFacade(EnrollmentService service,Metrics metrics,ObjectProvider<RedissonClient> clients,DatabaseConfig.Mode mode){
        this.service=service;this.metrics=metrics;this.redis=clients.getIfAvailable();this.mode=mode;
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
            if(mode==DatabaseConfig.Mode.A||mode==DatabaseConfig.Mode.B0||mode==DatabaseConfig.Mode.B)return action.get();
            if(redis==null)throw new Unavailable("REDIS_UNAVAILABLE",null);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            if(mode==DatabaseConfig.Mode.D)return locked(studentKey(r),"studentRedis",deadline,action);
            return locked(studentKey(r),"studentRedis",deadline,
                ()->locked(courseKey(r),"courseRedis",deadline,action));
        }finally{inFlight.decrementAndGet();}
    }
    private <T>T setNxLocked(String key,String phase,long deadline,Supplier<T> action){
        String token=java.util.UUID.randomUUID().toString();
        long start=System.nanoTime();boolean acquired=false;
        try{
            while(System.nanoTime()<deadline){
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                acquired=redis.<String>getBucket(key,org.redisson.client.codec.StringCodec.INSTANCE)
                    .setIfAbsent(token,java.time.Duration.ofSeconds(30));
                if(acquired)break;
                long remaining=deadline-System.nanoTime();
                if(remaining>0)TimeUnit.NANOSECONDS.sleep(Math.min(remaining,
                    TimeUnit.MILLISECONDS.toNanos(java.util.concurrent.ThreadLocalRandom.current().nextLong(20,41))));
            }
            if(!acquired)throw new Unavailable("REDIS_LOCK_TIMEOUT",null);
        }catch(InterruptedException e){
            Thread.currentThread().interrupt();throw new Unavailable("REDIS_LOCK_INTERRUPTED",e);
        }catch(Unavailable e){throw e;
        }catch(RuntimeException e){throw new Unavailable("REDIS_UNAVAILABLE",e);
        }finally{metrics.record(phase+".wait",System.nanoTime()-start,!acquired);}
        long held=System.nanoTime();
        try{return action.get();}
        finally{
            metrics.record(phase+".holdUntilCompletion",System.nanoTime()-held,false);
            try{
                Number removed=releaseSetNx(key,token);
                if(removed.longValue()!=1)metrics.record(phase+".unlockFailure",0,true);
            }catch(RuntimeException e){metrics.record(phase+".unlockFailure",0,true);}
        }
    }
    Number releaseSetNx(String key,String token){
        return redis.getScript(org.redisson.client.codec.StringCodec.INSTANCE).eval(
            RScript.Mode.READ_WRITE,
            "if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end",
            RScript.ReturnType.LONG,java.util.List.of(key),token);
    }
    private <T>T locked(String key,String phase,long deadline,Supplier<T> action){
        if(mode==DatabaseConfig.Mode.E)return setNxLocked(key,phase,deadline,action);
        RLock lock;
        long start=System.nanoTime();boolean acquired=false;
        try{
            lock=redis.getLock(key);
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
