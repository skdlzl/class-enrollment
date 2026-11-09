package com.jiyun.dbsplit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.*;

/** Test-only HTTP adapter. Calls the real proxied service; no fault hooks in production. */
public final class EnrollmentCrashServer {
    public static void main(String[] args) throws Exception {
        Path ready=Path.of(args[0]), reached=Path.of(args[1]);
        var app=new SpringApplication(ExperimentApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        var context=app.run(java.util.Arrays.copyOfRange(args,2,args.length));
        var json=new ObjectMapper();
        var service=context.getBean(EnrollmentService.class);
        var tx=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var executor=Executors.newFixedThreadPool(4);
        server.setExecutor(executor);
        server.createContext("/enroll",exchange->{
            int status=200;Object body;
            try {
                var request=json.readValue(exchange.getRequestBody(),EnrollmentService.Request.class);
                String phase=exchange.getRequestURI().getQuery();
                if("before-commit".equals(phase)) {
                    body=tx.execute(s->{
                        var result=service.enroll(request);
                        pause(reached,"before-commit",request.requestId());
                        return result;
                    });
                } else {
                    body=service.enroll(request); // Transaction interceptor has committed when this returns.
                    if("after-commit".equals(phase))pause(reached,"after-commit",request.requestId());
                }
            } catch(EnrollmentService.Rejected e) {status=409;body=Map.of("code",e.code);}
              catch(Exception e) {status=500;body=Map.of("error",e.getClass().getSimpleName());}
            byte[] bytes=json.writeValueAsBytes(body);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(status,bytes.length);
            try(var out=exchange.getResponseBody()){out.write(bytes);}
        });
        Runtime.getRuntime().addShutdownHook(new Thread(()->{server.stop(0);executor.shutdownNow();context.close();}));
        server.start();
        Files.writeString(ready,json.writeValueAsString(Map.of("pid",ProcessHandle.current().pid(),"port",server.getAddress().getPort())));
    }
    private static void pause(Path reached,String phase,String requestId) {
        try {
            Files.writeString(reached,phase+"\n"+requestId);
            // No release path: test must forcibly kill this owned JVM before the timeout.
            if(!new CountDownLatch(1).await(60,TimeUnit.SECONDS))throw new IllegalStateException("Crash injection timed out");
        } catch(Exception e) {throw new IllegalStateException(e);}
    }
}
