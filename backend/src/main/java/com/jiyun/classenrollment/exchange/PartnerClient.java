package com.jiyun.classenrollment.exchange;

import com.jiyun.classenrollment.enrollment.v2.RedisGate;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Partner contract: durable idempotent hold, confirm, cancel tombstone and status lookup. */
@Component
public class PartnerClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build();
    private final String url;
    private final io.micrometer.core.instrument.MeterRegistry metrics;
    public PartnerClient(String url) { this(url,new io.micrometer.core.instrument.simple.SimpleMeterRegistry()); }
    @org.springframework.beans.factory.annotation.Autowired
    public PartnerClient(@Value("${enrollment.external-url:http://localhost:8090}") String url,io.micrometer.core.instrument.MeterRegistry metrics) {
        this.url=url; this.metrics=metrics;
    }
    public String status(String id, long student, long course, long deadline) { return call("GET",id,student,course,"",deadline); }
    public String reserve(String id, long student, long course, long deadline) { return call("PUT",id,student,course,"reserve",deadline); }
    public String confirm(String id, long student, long course, long deadline) { return call("PUT",id,student,course,"confirm",deadline); }
    public String cancel(String id, long student, long course, long deadline) { return call("PUT",id,student,course,"cancel",deadline); }
    private String call(String method,String id,long student,long course,String action,long deadline) {
        long started=System.nanoTime();
        String outcome="OK";
        try {
        long ms=Math.min(600,(deadline-System.nanoTime())/1_000_000);
        if(ms<=0) throw RedisGate.failure("REQUEST_DEADLINE");
        var req=HttpRequest.newBuilder(URI.create(url+"/reservations/"+id+"?student="+student+"&course="+course+"&action="+action))
            .timeout(Duration.ofMillis(ms)).method(method,HttpRequest.BodyPublishers.noBody()).build();
        try {
            var res=http.send(req,HttpResponse.BodyHandlers.ofInputStream());
            try(var body=res.body()) {
                if(res.statusCode()!=200) throw RedisGate.failure("PARTNER_BAD_RESPONSE");
                // State is in a bounded header, so a stalled body cannot block recovery.
                String state=res.headers().firstValue("Reservation-State").orElse("");
                if(!java.util.Set.of("ABSENT","HELD","CONFIRMED","CANCELLED","FULL").contains(state))
                    throw RedisGate.failure("PARTNER_BAD_RESPONSE");
                return state;
            }
        } catch(HttpConnectTimeoutException e) { throw RedisGate.failure("PARTNER_CONNECT_TIMEOUT"); }
        catch(HttpTimeoutException e) { throw RedisGate.failure("PARTNER_RESPONSE_TIMEOUT"); }
        catch(IOException e) { throw RedisGate.failure("PARTNER_IO_ERROR"); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw RedisGate.failure("INTERRUPTED"); }
        } catch(com.jiyun.classenrollment.common.error.EnrollmentException e) {
            outcome=e.getCode(); throw e;
        } finally {
            metrics.timer("exchange.partner","action",action.isEmpty()?"status":action,"outcome",outcome)
                .record(System.nanoTime()-started,java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }
}
