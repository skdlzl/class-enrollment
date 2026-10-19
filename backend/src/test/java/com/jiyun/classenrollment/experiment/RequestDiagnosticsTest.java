package com.jiyun.classenrollment.experiment;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import jakarta.servlet.ServletException;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RequestDiagnosticsTest {
    @Test void recordsRedisTimingAndHandledFailure() throws Exception {
        var filter=new RequestDiagnostics();
        var request=new MockHttpServletRequest("POST","/api/enrollment-requests");
        filter.doFilter(request,new MockHttpServletResponse(),(req,res)->{
            req.setAttribute(RequestDiagnostics.REDIS_NANOS,2_000_000L);
            ((jakarta.servlet.http.HttpServletResponse)res).setStatus(503);
        });
        var values=(Map<?,?>)filter.snapshot().get("submit");
        assertEquals(1L,values.get("entered"));assertEquals(1L,values.get("completed"));
        assertEquals(1L,values.get("errors"));assertEquals(1L,values.get("redisCalls"));
        assertEquals(2.0,values.get("redisTotalMs"));
    }
    @Test void recordsThrownFailureWithoutInventingRedisCall() {
        var filter=new RequestDiagnostics();
        assertThrows(ServletException.class,()->filter.doFilter(new MockHttpServletRequest("GET","/api/courses"),
            new MockHttpServletResponse(),(req,res)->{throw new ServletException("test");}));
        var values=(Map<?,?>)filter.snapshot().get("catalog");
        assertEquals(1L,values.get("completed"));assertEquals(1L,values.get("errors"));
        assertEquals(0L,values.get("redisCalls"));
    }
    @Test void ignoresDiagnosticRequests() throws Exception {
        var filter=new RequestDiagnostics();
        filter.doFilter(new MockHttpServletRequest("GET","/api/experiment/diagnostics"),
            new MockHttpServletResponse(),(req,res)->{});
        assertTrue(filter.snapshot().isEmpty());
    }
}
