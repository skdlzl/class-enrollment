package com.jiyun.classenrollment.enrollment.v2;

import com.jiyun.classenrollment.exchange.PartnerClient;
import com.jiyun.classenrollment.common.error.EnrollmentException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PartnerClientTest {
    @Test void mutationCanSucceedEvenWhenResponseTimesOut() throws Exception {
        var state=new AtomicReference<>("ABSENT");
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var executor=Executors.newCachedThreadPool(); server.setExecutor(executor);
        server.createContext("/reservations/", exchange->{
            if(exchange.getRequestMethod().equals("PUT")) {
                state.set("HELD");
                try { Thread.sleep(850); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            exchange.getResponseHeaders().set("Reservation-State",state.get());
            try { exchange.sendResponseHeaders(200,-1); } finally { exchange.close(); }
        });
        server.start();
        try {
            var client=new PartnerClient("http://127.0.0.1:"+server.getAddress().getPort());
            assertEquals("PARTNER_RESPONSE_TIMEOUT",assertThrows(EnrollmentException.class,()->client.reserve("id",1,1,System.nanoTime()+TimeUnit.SECONDS.toNanos(3))).getCode());
            assertEquals("HELD",client.status("id",1,1,System.nanoTime()+TimeUnit.SECONDS.toNanos(3)));
        } finally { server.stop(0); executor.shutdownNow(); }
    }
    @Test void invalidPartnerStateCannotBeTreatedAsSuccess() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{exchange.sendResponseHeaders(200,-1);exchange.close();});
        server.start();
        try {
            var client=new PartnerClient("http://127.0.0.1:"+server.getAddress().getPort());
            assertEquals("PARTNER_BAD_RESPONSE",assertThrows(EnrollmentException.class,()->client.status("id",1,1,System.nanoTime()+TimeUnit.SECONDS.toNanos(3))).getCode());
        } finally { server.stop(0); }
    }
}
