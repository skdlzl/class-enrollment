package com.jiyun.classenrollment.experiment;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Map;

/** Explicitly enabled local experiments only. No application-table writes. */
@RestController
@ConditionalOnProperty(name="experiment.enabled", havingValue="true")
public class ExperimentController {
    private final JdbcTemplate jdbc;
    private final HikariDataSource pool;
    @Value("${enrollment.course-lock-strategy}") private String strategy;
    @Value("${enrollment.local-gate-enabled:false}") private boolean gate;
    @Value("${enrollment.queue.enabled:false}") private boolean queueEnabled;
    @Value("${enrollment.queue.batch-reject-enabled:true}") private boolean batchReject;
    @Value("${enrollment.capacity-precheck:true}") private boolean precheck;
    public ExperimentController(JdbcTemplate jdbc, DataSource source) throws SQLException {
        this.jdbc = jdbc;
        this.pool = source.unwrap(HikariDataSource.class);
    }
    @GetMapping("/api/experiment/read")
    public Map<String, Object> read() {
        return jdbc.queryForMap("SELECT id, name FROM students WHERE id=1");
    }
    @GetMapping("/api/experiment/pool")
    public Map<String, Object> pool() {
        var mx = pool.getHikariPoolMXBean();
        return Map.of("active", mx.getActiveConnections(), "idle", mx.getIdleConnections(),
                "waiting", mx.getThreadsAwaitingConnection(), "total", mx.getTotalConnections(),
                "max", pool.getMaximumPoolSize(), "strategy", strategy, "gate", gate,
                "precheck", precheck,"queueEnabled",queueEnabled,"batchReject",batchReject);
    }
}
