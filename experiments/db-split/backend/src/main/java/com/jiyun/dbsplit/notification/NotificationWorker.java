package com.jiyun.dbsplit.notification;

import com.jiyun.dbsplit.Metrics;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.TimeUnit;

@Component
public class NotificationWorker {
    public enum Strategy { DB_INLINE, DB_CLAIM, REDIS_OUTBOX, OPTIMISTIC }
    private final NotificationStore store;private final NotificationGateway gateway;
    private final RedissonClient redis;private final Metrics metrics;private final TransactionTemplate tx;
    private final boolean enabled,automatic;private final Strategy strategy;private final long leaseMs;private final int maxAttempts;
    public NotificationWorker(NotificationStore store,NotificationGateway gateway,ObjectProvider<RedissonClient> clients,
        Metrics metrics,PlatformTransactionManager manager,
        @Value("${experiment.notifications.enabled:false}") boolean enabled,
        @Value("${experiment.notifications.auto:false}") boolean automatic,
        @Value("${experiment.notifications.strategy:DB_CLAIM}") Strategy strategy,
        @Value("${experiment.notifications.lease-ms:30000}") long leaseMs,
        @Value("${experiment.notifications.max-attempts:5}") int maxAttempts){
        this.store=store;this.gateway=gateway;this.redis=clients.getIfAvailable();this.metrics=metrics;
        this.tx=new TransactionTemplate(manager);this.enabled=enabled;this.automatic=automatic;
        this.strategy=strategy;this.leaseMs=leaseMs;this.maxAttempts=maxAttempts;
        if(leaseMs<1||maxAttempts<1)throw new IllegalArgumentException("Invalid notification limits");
        if(enabled&&strategy==Strategy.REDIS_OUTBOX&&redis==null)throw new IllegalStateException("Redis notification guard missing");
    }
    @Scheduled(fixedDelayString="${experiment.notifications.poll-ms:500}")
    public void tick(){
        if(!enabled||!automatic)return;
        try{for(String id:store.candidates(10))process(id);}
        catch(RuntimeException e){metrics.record("notification.workerFailure",0,true);}
    }
    public boolean process(String id){return process(id,strategy);}
    // Public for the isolated comparison harness. Never exposed as an HTTP strategy selector.
    public boolean process(String id,Strategy selected){
        if(!enabled)return false;
        metrics.record("notification.processAttempt",0,false);
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Notification worker must run outside caller transactions");
        if(selected==Strategy.DB_INLINE){
            // Deliberately slow comparator: external HTTP under a DB row lock/connection.
            long start=System.nanoTime();
            try{return Boolean.TRUE.equals(tx.execute(s->{
                long wait=System.nanoTime();store.lockForInline(id);
                metrics.record("notification.rowLockWait",System.nanoTime()-wait,false);
                return deliver(id);
            }));}finally{metrics.record("notification.inlineTransaction",System.nanoTime()-start,false);}
        }
        if(selected==Strategy.DB_CLAIM)return deliver(id);
        if(selected==Strategy.OPTIMISTIC){
            metrics.record("notification.optimisticAttempt",0,false);
            Long version=store.eligibleVersion(id);
            if(version==null){metrics.record("notification.optimisticIneligible",0,false);return false;}
            long start=System.nanoTime();
            var job=store.claimVersion(id,version,leaseMs);
            metrics.record("notification.optimisticCas",System.nanoTime()-start,job==null);
            if(job==null){metrics.record("notification.versionConflict",0,false);return false;}
            return sendClaimed(job);
        }
        if(redis==null)throw new IllegalStateException("Redis required");
        var lock=redis.getLock("notification:"+id);
        boolean acquired=false;
        try{
            acquired=lock.tryLock(0,leaseMs,TimeUnit.MILLISECONDS);
            if(!acquired){metrics.record("notification.redisBusy",0,false);return false;}
            metrics.record("notification.redisAcquired",0,false);
            return deliver(id);
        }catch(InterruptedException e){Thread.currentThread().interrupt();return false;}
        finally{
            if(acquired){try{lock.unlock();}catch(RuntimeException e){metrics.record("notification.unlockFailure",0,true);}}
        }
    }
    private boolean deliver(String id){
        long claimStart=System.nanoTime();
        NotificationStore.Job job;
        try{job=store.claim(id,leaseMs);}
        catch(RuntimeException e){metrics.record("notification.claimError",0,true);throw e;}
        finally{metrics.record("notification.claimUpdateAttempt",System.nanoTime()-claimStart,false);}
        if(job==null){metrics.record("notification.claimRejected",0,false);return false;}
        return sendClaimed(job);
    }
    private boolean sendClaimed(NotificationStore.Job job){
        metrics.record("notification.claimAcquired",0,false);
        if(job.attempts()>maxAttempts){store.retry(job,"ATTEMPT_LIMIT_UNKNOWN",true,maxAttempts);return false;}
        long start=System.nanoTime();boolean success=false;
        try{
            metrics.record("notification.providerCall",0,false);
            gateway.send(job);
            success=store.sent(job);
            return success;
        }catch(NotificationGateway.DeliveryFailure e){store.retry(job,e.code,e.permanent,maxAttempts);return false;}
        finally{metrics.record("notification.delivery",System.nanoTime()-start,!success);}
        // If process/DB fails after the provider accepted, the lease expires and the same key is retried.
    }
}
