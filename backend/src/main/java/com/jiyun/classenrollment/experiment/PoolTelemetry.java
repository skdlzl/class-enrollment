package com.jiyun.classenrollment.experiment;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.IMetricsTracker;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.concurrent.*;

/** Aggregate connection acquisition duration, not SQL/row-lock time. Experiment only. */
@Component
@ConditionalOnProperty(name="experiment.enabled", havingValue="true")
public class PoolTelemetry {
    private static final Logger log = LoggerFactory.getLogger(PoolTelemetry.class);
    private final HikariDataSource source;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "experiment-pool-sampler"); t.setDaemon(true); return t;
    });
    @org.springframework.beans.factory.annotation.Autowired
    private EnrollmentTimings timings;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    @org.springframework.beans.factory.annotation.Qualifier("catalogDataSource")
    private HikariDataSource catalog;
    private final java.util.Map<HikariDataSource, Counters> counters = new java.util.LinkedHashMap<>();
    private static class Counters {
        long count,nanos,maxNanos,timeouts;
        synchronized void acquired(long value){count++;nanos+=value;maxNanos=Math.max(maxNanos,value);}
        synchronized void timeout(){timeouts++;}
        synchronized long[] drain(){long[] r={count,nanos,maxNanos,timeouts};count=0;nanos=0;maxNanos=0;timeouts=0;return r;}
    }
    public PoolTelemetry(DataSource source) throws SQLException {
        this.source = source.unwrap(HikariDataSource.class);
    }
    @PostConstruct void start() {
        attach(source,catalog==null?"pool":"pool.write");
        if(catalog!=null)attach(catalog,"pool.catalog");
        scheduler.scheduleAtFixedRate(this::sample,1,1,TimeUnit.SECONDS);
    }
    private void attach(HikariDataSource dataSource,String label) {
        if(dataSource.getMetricsTrackerFactory()!=null||dataSource.getMetricRegistry()!=null)
            throw new IllegalStateException("Experiment telemetry requires an unused Hikari metrics tracker");
        var metrics=new Counters();counters.put(dataSource,metrics);
        dataSource.setMetricsTrackerFactory((name,stats)->new IMetricsTracker(){
            @Override public void recordConnectionAcquiredNanos(long elapsed){metrics.acquired(elapsed);timings.record(label+".acquire",elapsed,false);}
            @Override public void recordConnectionUsageMillis(long elapsed){timings.record(label+".usage",TimeUnit.MILLISECONDS.toNanos(elapsed),false);}
            @Override public void recordConnectionTimeout(){metrics.timeout();}
        });
    }
    private void sample() {
        for(var entry:counters.entrySet())try {
            var source=entry.getKey();
            var pool = source.getHikariPoolMXBean();
            if (pool == null) continue;
            long[] m = entry.getValue().drain();
            log.info("POOL_SAMPLE pool={} max={} active={} idle={} waiting={} total={} acquireCount={} acquireTotalMs={} acquireMaxMs={} timeouts={}",
                source.getPoolName(), source.getMaximumPoolSize(), pool.getActiveConnections(), pool.getIdleConnections(),
                pool.getThreadsAwaitingConnection(), pool.getTotalConnections(), m[0], m[1]/1_000_000.0, m[2]/1_000_000.0, m[3]);
        } catch (RuntimeException e) { log.warn("POOL_SAMPLE_FAILED", e); }
    }
    @PreDestroy void stop() { scheduler.shutdownNow(); }
}
