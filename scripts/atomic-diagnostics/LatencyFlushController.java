package com.jiyun.classenrollment.common.config;

import javax.sql.DataSource;
import java.io.IOException;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Windows diagnostic JVMs bind to loopback; this endpoint only flushes evidence buffers. */
@RestController
@ConditionalOnProperty(name="analysis.enabled", havingValue="true")
public class LatencyFlushController {
    private final DataSource dataSource;
    private final Environment environment;
    public LatencyFlushController(DataSource dataSource, Environment environment) {
        this.dataSource=dataSource;
        this.environment=environment;
    }
    @GetMapping("/internal/analysis/identity")
    public Map<String,Object> identity() {
        return Map.of("pid",ProcessHandle.current().pid(),
                "folder",environment.getRequiredProperty("analysis.folder"),
                "javaVersion",System.getProperty("java.version"));
    }
    @PostMapping("/internal/analysis/flush")
    public void flush() throws IOException {
        if (!(dataSource instanceof LatencyDiagnostics.Observed observed))
            throw new IllegalStateException("JDBC observer is missing");
        synchronized(observed.events) { observed.events.flush(); }
        synchronized(observed.metrics) { observed.metrics.flush(); }
    }
}

