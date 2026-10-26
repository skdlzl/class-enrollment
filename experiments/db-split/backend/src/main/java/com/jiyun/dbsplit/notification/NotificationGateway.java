package com.jiyun.dbsplit.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;

@Component
public class NotificationGateway {
    public static class DeliveryFailure extends RuntimeException {
        final String code; final boolean permanent;
        DeliveryFailure(String code,boolean permanent,Throwable cause){super(code,cause);this.code=code;this.permanent=permanent;}
    }
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final String endpoint;
    private final long timeoutMs;
    public NotificationGateway(@Value("${experiment.notifications.url:}") String endpoint,
        @Value("${experiment.notifications.http-timeout-ms:3000}") long timeoutMs){
        this.endpoint=endpoint;this.timeoutMs=timeoutMs;
    }
    public void send(NotificationStore.Job job){
        if(endpoint.isBlank())throw new DeliveryFailure("PROVIDER_NOT_CONFIGURED",true,null);
        try{
            var request=HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type","application/json").header("Idempotency-Key",job.id())
                .POST(HttpRequest.BodyPublishers.ofString(job.payload())).build();
            var response=http.send(request,HttpResponse.BodyHandlers.discarding());
            if(response.statusCode()!=200 && response.statusCode()!=201)
                throw new DeliveryFailure("HTTP_"+response.statusCode(),
                    response.statusCode()>=400&&response.statusCode()<500&&response.statusCode()!=408&&response.statusCode()!=429,null);
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new DeliveryFailure("INTERRUPTED_UNKNOWN",false,e);}
        catch(java.io.IOException e){throw new DeliveryFailure("TRANSPORT_UNKNOWN",false,e);}
    }
}
