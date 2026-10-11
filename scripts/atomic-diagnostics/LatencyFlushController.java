package com.jiyun.classenrollment.common.config;

import javax.sql.DataSource;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Windows diagnostic JVMs bind to loopback; this endpoint only flushes evidence buffers. */
@RestController
@ConditionalOnProperty(name="analysis.enabled", havingValue="true")
public class LatencyFlushController {
    private final DataSource dataSource;
    public LatencyFlushController(DataSource dataSource) { this.dataSource=dataSource; }
    @PostMapping("/internal/analysis/flush")
    public void flush() throws IOException {
        if (!(dataSource instanceof LatencyDiagnostics.Observed observed))
            throw new IllegalStateException("JDBC observer is missing");
        synchronized(observed.events) { observed.events.flush(); }
        synchronized(observed.metrics) { observed.metrics.flush(); }
    }
}
