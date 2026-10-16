package com.jiyun.classenrollment.enrollment.queue;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.redisson.api.RedissonClient;
import org.redisson.api.RLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.*;

@Component
@ConditionalOnProperty(name="enrollment.queue.enabled",havingValue="true")
public class RedisIngressWorker {
    private static final Logger log=LoggerFactory.getLogger(RedisIngressWorker.class);
    private final RedisIngressStore store;private final IngressProcessor processor;private final RedissonClient redis;
    private volatile boolean running;
    private ExecutorService executor;
    @Value("${enrollment.queue.scheduler-enabled:true}") private boolean enabled=true;
    public RedisIngressWorker(RedisIngressStore store,IngressProcessor processor,RedissonClient redis){this.store=store;this.processor=processor;this.redis=redis;}
    @PostConstruct public void start(){
        if(!enabled)return;
        log.info("QUEUE_VERSION redis-ingress-v1 globalWorkers=2 receiptStore=REDIS");
        running=true;executor=Executors.newSingleThreadExecutor();executor.execute(()->{
            while(running&&!Thread.currentThread().isInterrupted()){
                if(!tick())try{Thread.sleep(200);}catch(InterruptedException e){Thread.currentThread().interrupt();return;}
            }
        });
    }
    public boolean tick(){
        RLock slot=null;
        try{
            if(!store.hasReady())return false;
            // Two global slots bound database writers across both application instances.
            for(int i=0;i<2;i++){var candidate=redis.getLock("enrollment:{ingress}:worker:"+i);if(candidate.tryLock()){slot=candidate;break;}}
            if(slot==null)return false;
            var claim=store.claim(30000);if(claim==null)return false;
            try{
                var result=processor.process(claim.ticket());
                store.finish(claim,result); // A stale owner cannot overwrite a newer Redis result.
            }catch(Exception error){
                log.warn("INGRESS_PROCESS_RETRY requestId={}",claim.ticket().requestId(),error);
                // Do not label an uncertain DB commit as FAILED. Replay reads the durable ledger.
                store.defer(claim);
            }
            return true;
        }catch(Exception error){log.warn("INGRESS_UNAVAILABLE",error);return false;}
        finally{if(slot!=null)try{if(slot.isHeldByCurrentThread())slot.unlock();}catch(Exception e){log.warn("INGRESS_SLOT_RELEASE_FAILED",e);}}
    }
    @PreDestroy public void stop(){running=false;if(executor!=null){executor.shutdown();try{if(!executor.awaitTermination(20,TimeUnit.SECONDS))executor.shutdownNow();}catch(InterruptedException e){Thread.currentThread().interrupt();executor.shutdownNow();}}}
}
