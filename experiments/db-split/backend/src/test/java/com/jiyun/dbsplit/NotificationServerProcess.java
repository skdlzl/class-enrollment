package com.jiyun.dbsplit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Owns only its child PID. No fixed ports or broad java.exe termination. */
final class NotificationServerProcess implements AutoCloseable {
 final Process process;final String processUrl,controlUrl;final long pid;
 final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
 static final ObjectMapper JSON=new ObjectMapper();
 NotificationServerProcess(HikariDataSource source,String redisUrl,String providerUrl,String strategy,int workers,int pool,Path directory,int node)throws Exception {
  Files.createDirectories(directory);var ready=directory.resolve("server-"+node+"-ready.json").toAbsolutePath();
  var config=Files.createTempFile("notification-server-",".json");
  var c=new LinkedHashMap<String,Object>();c.put("jdbcUrl",source.getJdbcUrl());c.put("username",source.getUsername());c.put("password",source.getPassword());
  c.put("redisUrl",redisUrl);c.put("providerUrl",providerUrl);c.put("strategy",strategy);c.put("workers",workers);c.put("pool",pool);
  c.put("readyFile",ready.toString());c.put("parentPid",ProcessHandle.current().pid());JSON.writeValue(config.toFile(),c);
  // Java argument file avoids Windows command-line length limits for the Maven test classpath.
  var args=Files.createTempFile("notification-server-",".args");
  String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
  Files.writeString(args,"-Xms128m\n-Xmx256m\n-cp\n"+quote(cp)+"\ncom.jiyun.dbsplit.NotificationLoadServer\n"+quote(config.toString())+"\n");
  Process child=null;
  try{
   String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
   child=new ProcessBuilder(java,"@"+args).redirectErrorStream(true).redirectOutput(directory.resolve("server-"+node+".log").toFile()).start();
   long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
   while(!Files.exists(ready)&&child.isAlive()&&System.nanoTime()<end)Thread.sleep(50);
   if(!Files.exists(ready)||!child.isAlive())throw new IllegalStateException("Notification child failed to start. See "+directory);
   var r=JSON.readTree(ready.toFile());pid=r.get("pid").asLong();if(pid!=child.pid())throw new IllegalStateException("Child PID mismatch");
   processUrl="http://127.0.0.1:"+r.get("processPort").asInt();controlUrl="http://127.0.0.1:"+r.get("controlPort").asInt();process=child;
   snapshot();
  }catch(Exception e){if(child!=null)stop(child);throw e;}
  finally{Files.deleteIfExists(config);Files.deleteIfExists(args);}
 }
 static String quote(String s){return "\""+s.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
 Map<String,Object> request(String url)throws Exception {
  var response=client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(),HttpResponse.BodyHandlers.ofString());
  if(response.statusCode()!=200)throw new IllegalStateException("HTTP "+response.statusCode()+": "+response.body());
  return JSON.readValue(response.body(),new TypeReference<Map<String,Object>>(){});
 }
 boolean process(String id)throws Exception{return Boolean.TRUE.equals(request(processUrl+"/process?"+id).get("sent"));}
 void read()throws Exception{request(controlUrl+"/read");}
 Map<String,Object> snapshot()throws Exception{return request(controlUrl+"/metrics");}
 static void stop(Process p)throws InterruptedException {
  p.destroy();if(!p.waitFor(10,TimeUnit.SECONDS)){p.destroyForcibly();if(!p.waitFor(10,TimeUnit.SECONDS))throw new IllegalStateException("Child PID "+p.pid()+" failed to stop");}
 }
 public void close()throws Exception{stop(process);}
}
