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
    @org.springframework.beans.factory.annotation.Autowired
    private com.jiyun.classenrollment.enrollment.application.EnrollmentDbAdmission admission;
    @org.springframework.beans.factory.annotation.Autowired
    private EnrollmentTimings timings;
    @org.springframework.beans.factory.annotation.Autowired
    private com.jiyun.classenrollment.enrollment.application.ClosedCourseHints closedCourseHints;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    @org.springframework.beans.factory.annotation.Qualifier("catalogDataSource")
    private HikariDataSource catalogPool;
    private final JdbcTemplate jdbc;
    private final RequestDiagnostics diagnostics;
    private final HikariDataSource pool;
    @Value("${enrollment.course-lock-strategy}") private String strategy;
    @Value("${enrollment.local-gate-enabled:false}") private boolean gate;
    @Value("${enrollment.queue.enabled:false}") private boolean queueEnabled;
    @Value("${enrollment.queue.batch-reject-enabled:true}") private boolean batchReject;
    @Value("${enrollment.capacity-precheck:true}") private boolean precheck;
    public ExperimentController(JdbcTemplate jdbc, DataSource source, RequestDiagnostics diagnostics) throws SQLException {
        this.jdbc = jdbc;
        this.diagnostics = diagnostics;
        this.pool = source.unwrap(HikariDataSource.class);
    }
    @GetMapping("/api/experiment/diagnostics")
    public Map<String,Object> diagnostics() {
        var result = new java.util.LinkedHashMap<String,Object>(diagnostics.snapshot());
        result.put("dbAdmission", admission.snapshot());
        result.put("enrollmentTimings", timings.snapshot());
        result.put("closedCourseHint", closedCourseHints.snapshot());
        return result;
    }
    @GetMapping("/api/experiment/read")
    public Map<String, Object> read() {
        return jdbc.queryForMap("SELECT id, name FROM students WHERE id=1");
    }
    @GetMapping("/api/experiment/pool")
    public Map<String, Object> pool() {
        var write=poolState(pool);
        var read=catalogPool==null?null:poolState(catalogPool);
        var result = new java.util.LinkedHashMap<String,Object>();
        for(String key:new String[]{"active","idle","waiting","total","max"})
            result.put(key,write.get(key)+(read==null?0:read.get(key)));
        result.put("strategy",strategy);result.put("gate",gate);result.put("precheck",precheck);
        result.put("queueEnabled",queueEnabled);result.put("batchReject",batchReject);
        result.put("queryPoolEnabled",catalogPool!=null);
        result.put("queryPoolSize",catalogPool==null?0:catalogPool.getMaximumPoolSize());
        result.put("pools",catalogPool==null?Map.of("shared",write):Map.of("write",write,"catalog",read));
        result.put("dbAdmission",admission.snapshot());
        result.put("closedCourseHint",closedCourseHints.snapshot());
        return result;
    }
    private Map<String,Integer> poolState(HikariDataSource source) {
        var mx=source.getHikariPoolMXBean();
        return Map.of("active",mx==null?0:mx.getActiveConnections(),"idle",mx==null?0:mx.getIdleConnections(),
            "waiting",mx==null?0:mx.getThreadsAwaitingConnection(),"total",mx==null?0:mx.getTotalConnections(),
            "max",source.getMaximumPoolSize());
    }
}
