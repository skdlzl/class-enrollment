package com.jiyun.classenrollment.experiment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/** Experiment-only cumulative fixed-phase timings. No request IDs, SQL or unbounded samples. */
@Component
public class EnrollmentTimings {
    private final boolean enabled;
    private final ThreadLocal<Long> proxyStarted = new ThreadLocal<>();
    private final Map<String, Counter> phases = new ConcurrentHashMap<>();
    private static class Counter {
        final LongAdder count=new LongAdder(), failures=new LongAdder(), nanos=new LongAdder();
        final AtomicLong max=new AtomicLong();
    }
    public EnrollmentTimings(@Value("${experiment.enabled:false}") boolean enabled) {this.enabled=enabled;}
    public void record(String phase,long nanos,boolean failed) {
        if(!enabled)return;
        Counter c=phases.computeIfAbsent(phase,k->new Counter());
        c.nanos.add(Math.max(0,nanos));c.max.accumulateAndGet(Math.max(0,nanos),Math::max);
        if(failed)c.failures.increment();c.count.increment();
    }
    public <T> T measure(String phase,Supplier<T> action) {
        if(!enabled)return action.get();
        long start=System.nanoTime();boolean failed=true;
        try {T value=action.get();failed=false;return value;}
        finally {record(phase,System.nanoTime()-start,failed);}
    }
    public <T> T proxy(Supplier<T> transaction) {
        if(!enabled)return transaction.get();
        Long previous=proxyStarted.get();proxyStarted.set(System.nanoTime());
        try {return measure("transactionProxy",transaction);}
        finally {if(previous==null)proxyStarted.remove();else proxyStarted.set(previous);}
    }
    public Body body() {
        long start=enabled?System.nanoTime():0;
        if(enabled && proxyStarted.get()!=null)record("transactionEntry",start-proxyStarted.get(),false);
        Body body=new Body(start);
        if(enabled && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                private long beforeCommit;
                @Override public void beforeCommit(boolean readOnly){beforeCommit=System.nanoTime();}
                @Override public void afterCompletion(int status) {
                    long now=System.nanoTime();
                    String result=status==STATUS_COMMITTED?"committed":status==STATUS_ROLLED_BACK?"rolledBack":"unknown";
                    if(body.finished!=0)record("completionTail."+result,now-body.finished,false);
                    if(beforeCommit!=0)record("commitCallbacks."+result,now-beforeCommit,false);
                }
            });
        }
        return body;
    }
    public final class Body implements AutoCloseable {
        private final long started;private long finished;
        private Body(long started){this.started=started;}
        @Override public void close(){if(enabled){finished=System.nanoTime();record("transactionBody",finished-started,false);}}
    }
    public Map<String,Object> snapshot() {
        Map<String,Object> result=new LinkedHashMap<>();
        phases.forEach((key,c)->result.put(key,Map.of("count",c.count.sum(),"failures",c.failures.sum(),
            "totalMs",c.nanos.sum()/1_000_000.0,"maxMs",c.max.get()/1_000_000.0)));
        return Map.of("version","enrollment-phases-v1","enabled",enabled,"phases",result);
    }
}
