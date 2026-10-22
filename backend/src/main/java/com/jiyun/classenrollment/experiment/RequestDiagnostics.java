package com.jiyun.classenrollment.experiment;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Cumulative local diagnostic counters. No request payloads or per-request logging. */
@Component
@ConditionalOnProperty(name="experiment.enabled", havingValue="true")
public class RequestDiagnostics extends OncePerRequestFilter {
    public static final String REDIS_NANOS = RequestDiagnostics.class.getName()+".redisNanos";
    private final Map<String, Counters> groups = new ConcurrentHashMap<>();
    static class Counters {
        final LongAdder entered=new LongAdder(), completed=new LongAdder(), errors=new LongAdder();
        final LongAdder httpNanos=new LongAdder(), redisCalls=new LongAdder(), redisNanos=new LongAdder();
        Map<String,Object> snapshot() {
            return Map.of("entered",entered.sum(),"completed",completed.sum(),"errors",errors.sum(),
                "httpTotalMs",httpNanos.sum()/1_000_000.0,"redisCalls",redisCalls.sum(),
                "redisTotalMs",redisNanos.sum()/1_000_000.0);
        }
    }
    public Map<String,Object> snapshot() {
        Map<String,Object> result=new LinkedHashMap<>();
        groups.forEach((key,value)->result.put(key,value.snapshot()));
        return result;
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        String path=request.getRequestURI();
        String group=path.equals("/api/enrollments")&&request.getMethod().equals("POST")?"syncSubmit":
            path.equals("/api/enrollment-requests")&&request.getMethod().equals("POST")?"submit":
            path.equals("/api/courses")?"catalog":path.startsWith("/api/enrollment-requests/")?"queueRead":null;
        if(group==null){chain.doFilter(request,response);return;}
        Counters c=groups.computeIfAbsent(group,key->new Counters());
        c.entered.increment();long start=System.nanoTime();boolean threw=true;
        try {chain.doFilter(request,response);threw=false;}
        finally {
            c.httpNanos.add(System.nanoTime()-start);c.completed.increment();
            if(threw||response.getStatus()>=400)c.errors.increment();
            Object redis=request.getAttribute(REDIS_NANOS);
            if(redis instanceof Long nanos){c.redisCalls.increment();c.redisNanos.add(nanos);}
        }
    }
}
