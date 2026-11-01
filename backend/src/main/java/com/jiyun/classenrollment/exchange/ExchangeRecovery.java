package com.jiyun.classenrollment.exchange;

import java.util.Set;
import java.util.concurrent.*;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="enrollment.exchange-recovery",havingValue="true",matchIfMissing=true)
public class ExchangeRecovery {
    private final ExchangeService service;
    private final Set<String> submitted=ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(32,32,0L,TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(128), r->{var t=new Thread(r,"exchange-recovery");t.setDaemon(true);return t;},
        new ThreadPoolExecutor.AbortPolicy());
    public ExchangeRecovery(ExchangeService service) { this.service=service; }
    @Scheduled(fixedDelayString="${enrollment.exchange-recovery-delay-ms:25}") public void recover() {
        for(String id:service.recoveryCandidates()) {
            if(!submitted.add(id)) continue;
            try {
                workers.execute(()->{
                    try { service.process(id); }
                    catch(RuntimeException e) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("event=exchange_worker_failed request={} type={}",id,e.getClass().getSimpleName()); }
                    finally { submitted.remove(id); }
                });
            } catch(RejectedExecutionException e) { submitted.remove(id); }
        }
    }
    @Scheduled(fixedDelay=250) public void closeFull() { service.closeUntouchedFull(); }
    @PreDestroy public void close() { workers.shutdownNow(); }
}
