package com.jiyun.dbsplit;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.IMetricsTracker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;

@Component
public class Metrics implements BeanPostProcessor {
    private final boolean enabled;
    private final Map<String,HikariDataSource> pools=new ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentLinkedDeque<Map<String,Object>> samples=new java.util.concurrent.ConcurrentLinkedDeque<>();
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=1000)
    public void samplePool(){
        if(!enabled)return;
        pools.forEach((name,ds)->{
            var p=ds.getHikariPoolMXBean();if(p==null)return;
            samples.add(Map.of("timestamp",System.currentTimeMillis(),"pool",name,"active",p.getActiveConnections(),"waiting",p.getThreadsAwaitingConnection()));
        });
        while(samples.size()>720)samples.pollFirst();
    }
    public List<Map<String,Object>> poolState(){
        return pools.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e->{
            var ds=e.getValue();var p=ds.getHikariPoolMXBean();
            return Map.<String,Object>of("name",e.getKey(),"max",ds.getMaximumPoolSize(),"active",p==null?0:p.getActiveConnections(),"waiting",p==null?0:p.getThreadsAwaitingConnection(),"connectionTimeoutMs",ds.getConnectionTimeout());
        }).toList();
    }
    public List<Map<String,Object>> poolSamples(){return List.copyOf(samples);}
    private final Map<String,Counter> counters=new ConcurrentHashMap<>();
    private static class Counter {final LongAdder count=new LongAdder(),nanos=new LongAdder(),failures=new LongAdder();final AtomicLong max=new AtomicLong();}
    public Metrics(@Value("${experiment.metrics-enabled:true}") boolean enabled){this.enabled=enabled;}
    public void record(String name,long nanos,boolean failed){
        if(!enabled)return;
        var c=counters.computeIfAbsent(name,k->new Counter());c.nanos.add(Math.max(0,nanos));
        c.max.accumulateAndGet(nanos,Math::max);if(failed)c.failures.increment();c.count.increment();
    }
    public <T>T time(String name,Supplier<T> task){long s=System.nanoTime();boolean failed=true;
        try{T r=task.get();failed=false;return r;}finally{record(name,System.nanoTime()-s,failed);}}
    @Override public Object postProcessBeforeInitialization(Object bean,String name){
        if(bean instanceof HikariDataSource ds){String prefix=ds.getPoolName();pools.put(prefix,ds);if(enabled)ds.setMetricsTrackerFactory((pool,stats)->new IMetricsTracker(){
            public void recordConnectionAcquiredNanos(long n){record(prefix+".acquire",n,false);}
            public void recordConnectionUsageMillis(long ms){record(prefix+".usage",ms*1000000,false);}
            public void recordConnectionTimeout(){record(prefix+".timeout",0,true);}
        });}
        return bean;
    }
    public Map<String,Object> snapshot(){var result=new TreeMap<String,Object>();
        counters.forEach((k,c)->result.put(k,Map.of("count",c.count.sum(),"failures",c.failures.sum(),
            "totalMs",c.nanos.sum()/1e6,"lifetimeMaxMs",c.max.get()/1e6)));
        return Map.of("enabled",enabled,"phases",result);
    }
}
