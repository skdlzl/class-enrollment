package com.jiyun.dbsplit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiyun.dbsplit.notification.*;
import com.sun.net.httpserver.*;
import com.zaxxer.hikari.HikariDataSource;
import org.redisson.Redisson;
import org.redisson.config.Config;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only HTTP server. Each instance runs in its own JVM with its own pool and Redis client. */
public class NotificationLoadServer {
 public static void main(String[] args) throws Exception {
  var mapper=new ObjectMapper();var cfg=mapper.readTree(Path.of(args[0]).toFile());
  boolean unprotected=cfg.get("strategy").asText().equals("UNPROTECTED");
  var strategy=unprotected?NotificationWorker.Strategy.DB_CLAIM:NotificationWorker.Strategy.valueOf(cfg.get("strategy").asText());
  var metrics=new Metrics(true);var pool=new HikariDataSource();
  pool.setJdbcUrl(cfg.get("jdbcUrl").asText());pool.setUsername(cfg.get("username").asText());pool.setPassword(cfg.get("password").asText());
  pool.setMaximumPoolSize(cfg.get("pool").asInt());pool.setMinimumIdle(cfg.get("pool").asInt());
  pool.setConnectionTimeout(5000);pool.setTransactionIsolation("TRANSACTION_READ_COMMITTED");pool.setPoolName("loadPool");
  metrics.postProcessBeforeInitialization(pool,"loadPool");var jdbc=new JdbcTemplate(pool);jdbc.queryForObject("SELECT 1",Integer.class);
  RedissonClient redis=null;var beans=new StaticListableBeanFactory();
  if(strategy==NotificationWorker.Strategy.REDIS_OUTBOX){
   var rc=new Config();rc.setThreads(2);rc.setNettyThreads(2);rc.useSingleServer().setAddress(cfg.get("redisUrl").asText()).setConnectionMinimumIdleSize(2).setConnectionPoolSize(8);
   redis=Redisson.create(rc);beans.addBean("redis",redis);
  }
  final RedissonClient redisToClose=redis;
  var worker=new NotificationWorker(new NotificationStore(jdbc),new NotificationGateway(cfg.get("providerUrl").asText(),3000),
   beans.getBeanProvider(RedissonClient.class),metrics,new DataSourceTransactionManager(pool),true,false,strategy,30000,5);
  var executor=(ThreadPoolExecutor)Executors.newFixedThreadPool(cfg.get("workers").asInt());var controlExecutor=Executors.newFixedThreadPool(2);
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(executor);
  var control=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);control.setExecutor(controlExecutor);
  var peakWaiting=new AtomicInteger();var peakActive=new AtomicInteger();var sampler=Executors.newSingleThreadScheduledExecutor();
  long parent=cfg.get("parentPid").asLong();
  var parentCheck=new java.util.concurrent.atomic.AtomicLong();
  sampler.scheduleWithFixedDelay(()->{
   if(System.nanoTime()-parentCheck.get()>TimeUnit.SECONDS.toNanos(1)&&!ProcessHandle.of(parent).map(ProcessHandle::isAlive).orElse(false)){System.exit(2);return;}
   if(System.nanoTime()-parentCheck.get()>TimeUnit.SECONDS.toNanos(1))parentCheck.set(System.nanoTime());
   peakWaiting.accumulateAndGet(pool.getHikariPoolMXBean().getThreadsAwaitingConnection(),Math::max);
   peakActive.accumulateAndGet(pool.getHikariPoolMXBean().getActiveConnections(),Math::max);
  },0,10,TimeUnit.MILLISECONDS);
  // Test-only barriers: never part of the production notification worker.
  var gate=new java.util.concurrent.atomic.AtomicReference<CountDownLatch>(new CountDownLatch(0));
  var atGate=new AtomicInteger();
  control.createContext("/race-arm",exchange->{atGate.set(0);gate.set(new CountDownLatch(1));reply(exchange,200,Map.of("ok",true),mapper);});
  control.createContext("/race-state",exchange->{reply(exchange,200,Map.of("entered",atGate.get()),mapper);});
  control.createContext("/race-release",exchange->{gate.get().countDown();reply(exchange,200,Map.of("ok",true),mapper);});
  server.createContext("/process",exchange->{
   try{
    String id=exchange.getRequestURI().getQuery();UUID.fromString(id);
    boolean sent;
    if(unprotected){
     // Reproduce check-then-act: both JVMs read PENDING before either may call HTTP.
     var jobs=jdbc.query("SELECT id,payload FROM notification_outbox WHERE id=? AND status='PENDING'",
      (rs,i)->new NotificationStore.Job(rs.getString(1),rs.getString(2),"unsafe",1),id);
     if(jobs.isEmpty()){sent=false;}else{
      atGate.incrementAndGet();if(!gate.get().await(15,TimeUnit.SECONDS))throw new IllegalStateException("Race barrier timeout");
      new NotificationGateway(cfg.get("providerUrl").asText(),3000).send(jobs.get(0));
      jdbc.update("UPDATE notification_outbox SET status='SENT',sent_at=CURRENT_TIMESTAMP(6) WHERE id=?",id);sent=true;
     }
    }else{
     atGate.incrementAndGet();if(!gate.get().await(15,TimeUnit.SECONDS))throw new IllegalStateException("Race barrier timeout");
     sent=worker.process(id);
    }
    reply(exchange,200,Map.of("sent",sent),mapper);
   }
   catch(Exception e){reply(exchange,500,Map.of("error",e.getClass().getSimpleName()),mapper);}
  });
  control.createContext("/read",exchange->{
   try{jdbc.queryForObject("SELECT 1",Integer.class);reply(exchange,200,Map.of("ok",true),mapper);}
   catch(Exception e){reply(exchange,500,Map.of("error",e.getClass().getSimpleName()),mapper);}
  });
  control.createContext("/reset-peaks",exchange->{peakWaiting.set(0);peakActive.set(0);reply(exchange,200,Map.of("ok",true),mapper);});
  control.createContext("/metrics",exchange->{
   var result=new LinkedHashMap<String,Object>(metrics.snapshot());result.put("pid",ProcessHandle.current().pid());
   result.put("requestQueueLength",executor.getQueue().size());result.put("activeRequestWorkers",executor.getActiveCount());
   result.put("peakPoolWaiting",peakWaiting.get());result.put("peakPoolActive",peakActive.get());
   reply(exchange,200,result,mapper);
  });
  Runtime.getRuntime().addShutdownHook(new Thread(()->{
   server.stop(0);control.stop(0);executor.shutdownNow();controlExecutor.shutdownNow();sampler.shutdownNow();
   if(redisToClose!=null)redisToClose.shutdown();pool.close();
  }));
  server.start();control.start();
  var ready=Path.of(cfg.get("readyFile").asText());var tmp=Path.of(ready+".tmp");
  mapper.writeValue(tmp.toFile(),Map.of("pid",ProcessHandle.current().pid(),"processPort",server.getAddress().getPort(),"controlPort",control.getAddress().getPort()));
  Files.move(tmp,ready,StandardCopyOption.REPLACE_EXISTING);
  new CountDownLatch(1).await();
 }
 static void reply(HttpExchange exchange,int status,Object body,ObjectMapper mapper) throws java.io.IOException {
  byte[] bytes=mapper.writeValueAsBytes(body);exchange.getResponseHeaders().set("Content-Type","application/json");
  exchange.sendResponseHeaders(status,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}finally{exchange.close();}
 }
}
