package com.jiyun.classenrollment.enrollment.v2;

import com.jiyun.classenrollment.common.error.EnrollmentException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EligibilityClientTest {
    @Test void actualHttpResponseTimeoutIsClassified() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/eligibility",exchange -> {
            try { Thread.sleep(200); exchange.sendResponseHeaders(204,-1); }
            catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var client=new EligibilityClient("http://127.0.0.1:"+server.getAddress().getPort(),50);
            var ex=assertThrows(EnrollmentException.class,()->client.verify(1,1,System.nanoTime()+TimeUnit.SECONDS.toNanos(5)));
            assertEquals("EXTERNAL_RESPONSE_TIMEOUT",ex.getCode());
        } finally { server.stop(0); }
    }
    @Test void externalDenialIsBusinessFailure() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/eligibility",exchange -> { exchange.sendResponseHeaders(403,-1);exchange.close(); });
        server.start();
        try {
            var client=new EligibilityClient("http://127.0.0.1:"+server.getAddress().getPort(),1000);
            assertEquals("EXTERNAL_INELIGIBLE",assertThrows(EnrollmentException.class,()->client.verify(1,1,System.nanoTime()+TimeUnit.SECONDS.toNanos(5))).getCode());
        } finally { server.stop(0); }
    }
}
