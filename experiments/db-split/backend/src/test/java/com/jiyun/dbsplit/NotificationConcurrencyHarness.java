package com.jiyun.dbsplit;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

final class NotificationConcurrencyHarness {
 static void run(HikariDataSource source,String redisUrl,String run)throws Exception {
  var rows=new ArrayList<Map<String,Object>>();
  var report=new LinkedHashMap<String,Object>();report.put("title","동시성 테스트");report.put("plan","NOTIFICATION-CONCURRENCY-v1");
  report.put("provider","local idempotent mock; API calls and unique effects counted separately");
  report.put("barrier","UNPROTECTED: after both PENDING reads; guarded: before worker entry. Forced interleaving reproduction, not performance measurement.");
  report.put("results",rows);var output=Path.of("target","notification-concurrency.json");
  try{
   for(String strategy:List.of("UNPROTECTED","DB_INLINE","OPTIMISTIC","REDIS_OUTBOX")){
    try(var provider=new NotificationSafetyTest.Provider()){
     provider.delayMs=200;
     var directory=Path.of("target","notification-servers",run,"concurrency-"+strategy);
     try(var one=new NotificationServerProcess(source,redisUrl,provider.url(),strategy,2,4,directory,1);
         var two=new NotificationServerProcess(source,redisUrl,provider.url(),strategy,2,4,directory,2)){
      assertNotEquals(one.pid,two.pid);assertNotEquals(ProcessHandle.current().pid(),one.pid);assertNotEquals(ProcessHandle.current().pid(),two.pid);
      var nodes=List.of(one,two);var jdbc=new JdbcTemplate(source);
      for(int round=1;round<=3;round++){
       String id=UUID.randomUUID().toString();jdbc.update("INSERT INTO notification_outbox(id,request_id,run_id,event_type,payload) VALUES(?,?,?,'CONFIRMED','{}')",id,id,run);
       provider.calls.set(0);provider.delivered.clear();
       for(var node:nodes)node.request(node.controlUrl+"/race-arm");
       var executor=Executors.newFixedThreadPool(2);
       try{
        var first=executor.submit(()->one.process(id));var second=executor.submit(()->two.process(id));
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);boolean ready=false;
        while(System.nanoTime()<deadline){
         ready=((Number)one.request(one.controlUrl+"/race-state").get("entered")).intValue()==1&&
          ((Number)two.request(two.controlUrl+"/race-state").get("entered")).intValue()==1;
         if(ready)break;Thread.sleep(10);
        }
        assertTrue(ready,"Both independent JVMs must reach barrier");
        for(var node:nodes)node.request(node.controlUrl+"/race-release");
        boolean a=first.get(10,TimeUnit.SECONDS),b=second.get(10,TimeUnit.SECONDS);
        int calls=provider.calls.get(),effects=provider.delivered.size();
        String status=jdbc.queryForObject("SELECT status FROM notification_outbox WHERE id=?",String.class,id);
        // A later replay also must not make another provider request.
        boolean replay=one.process(id);int afterReplay=provider.calls.get();
        var row=new LinkedHashMap<String,Object>();row.put("round",round);
        row.put("strategy",strategy.equals("DB_INLINE")?"PESSIMISTIC":strategy.equals("REDIS_OUTBOX")?"REDIS":strategy);
        row.put("serverPids",List.of(one.pid,two.pid));row.put("bothReachedBarrier",ready);row.put("apiCalls",calls);
        row.put("providerUniqueEffects",effects);row.put("successfulHandlers",(a?1:0)+(b?1:0));row.put("databaseStatus",status);
        row.put("apiCallsAfterReplay",afterReplay);row.put("replayProcessed",replay);rows.add(row);
        assertEquals(strategy.equals("UNPROTECTED")?2:1,calls);assertEquals(1,effects);assertEquals("SENT",status);
        assertEquals(strategy.equals("UNPROTECTED")?2:1,(a?1:0)+(b?1:0));assertFalse(replay);assertEquals(calls,afterReplay);
       }finally{
        for(var node:nodes)node.request(node.controlUrl+"/race-release");executor.shutdownNow();executor.awaitTermination(10,TimeUnit.SECONDS);
       }
      }
     }
    }
   }
   report.put("passed",true);
  }finally{
   Files.createDirectories(output.getParent());new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(),report);
  }
 }
}
