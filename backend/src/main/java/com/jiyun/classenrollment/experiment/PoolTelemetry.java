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
    private long count, nanos, maxNanos, timeouts;
    public PoolTelemetry(DataSource source) throws SQLException {
        this.source = source.unwrap(HikariDataSource.class);
    }
    private synchronized void acquired(long value) { count++; nanos += value; maxNanos = Math.max(maxNanos, value); }
    private synchronized void timeout() { timeouts++; }
    private synchronized long[] drain() {
        long[] result = {count,nanos,maxNanos,timeouts}; count=0; nanos=0; maxNanos=0; timeouts=0; return result;
    }
    @PostConstruct void start() {
        // Do not overwrite an existing metrics integration.
        if (source.getMetricsTrackerFactory() != null || source.getMetricRegistry() != null) {
            throw new IllegalStateException("Experiment telemetry requires an unused Hikari metrics tracker");
        }
        source.setMetricsTrackerFactory((name, stats) -> new IMetricsTracker() {
            @Override public void recordConnectionAcquiredNanos(long elapsed) { acquired(elapsed); }
            @Override public void recordConnectionTimeout() { timeout(); }
        });
        scheduler.scheduleAtFixedRate(this::sample, 1, 1, TimeUnit.SECONDS);
    }
    private void sample() {
        try {
            var pool = source.getHikariPoolMXBean();
            if (pool == null) return;
            long[] m = drain();
            log.info("POOL_SAMPLE pool={} max={} active={} idle={} waiting={} total={} acquireCount={} acquireTotalMs={} acquireMaxMs={} timeouts={}",
                source.getPoolName(), source.getMaximumPoolSize(), pool.getActiveConnections(), pool.getIdleConnections(),
                pool.getThreadsAwaitingConnection(), pool.getTotalConnections(), m[0], m[1]/1_000_000.0, m[2]/1_000_000.0, m[3]);
        } catch (RuntimeException e) { log.warn("POOL_SAMPLE_FAILED", e); }
    }
    @PreDestroy void stop() { scheduler.shutdownNow(); }
}
