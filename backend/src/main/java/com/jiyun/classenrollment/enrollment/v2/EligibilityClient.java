package com.jiyun.classenrollment.enrollment.v2;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Read-only external academic eligibility API. No remote reservation or payment. */
@Component
public class EligibilityClient {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build();
    private final String url;
    private final long timeoutMs;
    public EligibilityClient(@Value("${enrollment.external-url:http://localhost:8090}") String url,
                             @Value("${enrollment.external-timeout-ms:600}") long timeoutMs) {
        this.url = url; this.timeoutMs = timeoutMs;
    }
    public void verify(long student, long course, long deadline) {
        long remaining = Math.min(timeoutMs, (deadline - System.nanoTime()) / 1_000_000);
        if (remaining <= 0) throw RedisGate.failure("REQUEST_DEADLINE");
        var request = HttpRequest.newBuilder(URI.create(url + "/eligibility?student=" + student + "&course=" + course))
            .timeout(Duration.ofMillis(remaining)).GET().build();
        try {
            var result = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (result.statusCode() == 403) throw new com.jiyun.classenrollment.common.error.EnrollmentException(
                "EXTERNAL_INELIGIBLE", "외부 학사 검증 거절", org.springframework.http.HttpStatus.CONFLICT);
            if (result.statusCode() != 204) throw RedisGate.failure("EXTERNAL_BAD_RESPONSE");
        } catch (HttpConnectTimeoutException e) { throw RedisGate.failure("EXTERNAL_CONNECT_TIMEOUT"); }
        catch (HttpTimeoutException e) { throw RedisGate.failure("EXTERNAL_RESPONSE_TIMEOUT"); }
        catch (IOException e) { throw RedisGate.failure("EXTERNAL_IO_ERROR"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw RedisGate.failure("INTERRUPTED"); }
    }
}
