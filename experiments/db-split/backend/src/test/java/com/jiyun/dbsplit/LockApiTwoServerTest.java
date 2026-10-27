package com.jiyun.dbsplit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.*;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in smoke uses the real Spring Boot application in two independent JVMs for all three strategies. */
@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties={"experiment.mode=B"})
@EnabledIfSystemProperty(named="lock.api.two-server",matches="true")
class LockApiTwoServerTest {
 @Container static final MySQLContainer<?> enrollment=new MySQLContainer<>("mysql:8.4");
 @Container static final MySQLContainer<?> academic=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> redis=new GenericContainer<>("redis:7.4.2").withExposedPorts(6379);
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",enrollment::getJdbcUrl);r.add("spring.datasource.username",enrollment::getUsername);r.add("spring.datasource.password",enrollment::getPassword);
  r.add("experiment.academic.url",academic::getJdbcUrl);r.add("experiment.academic.username",academic::getUsername);r.add("experiment.academic.password",academic::getPassword);
 }
 @Autowired JdbcTemplate jdbc;@Autowired FixtureService fixture;
 final ObjectMapper json=new ObjectMapper();final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
 class Server implements AutoCloseable {
  final Process process;final String base;
  Server(String strategy,String api,int index)throws Exception{
   var dir=Path.of("target","lock-api-servers",strategy);Files.createDirectories(dir);var log=dir.resolve(index+".log");
   var args=Files.createTempFile("lock-api-boot-",".args");
   var values=List.of("-Xms128m","-Xmx256m","-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),
    "com.jiyun.dbsplit.ExperimentApplication","--server.port=0","--experiment.mode=B","--experiment.revision=two-server-smoke",
    "--experiment.lock-api.strategy="+strategy,"--experiment.lock-api.url="+api,
    "--spring.datasource.url="+enrollment.getJdbcUrl(),"--spring.datasource.username="+enrollment.getUsername(),"--spring.datasource.password="+enrollment.getPassword(),
    "--experiment.academic.url="+academic.getJdbcUrl(),"--experiment.academic.username="+academic.getUsername(),"--experiment.academic.password="+academic.getPassword(),
    "--experiment.redis.address=redis://"+redis.getHost()+":"+redis.getMappedPort(6379));
   Files.writeString(args,String.join("\n",values.stream().map(NotificationServerProcess::quote).toList()));
   Process child=null;
   try{
    child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),"@"+args)
     .redirectErrorStream(true).redirectOutput(log.toFile()).start();
    String url=null;long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);
    while(child.isAlive()&&System.nanoTime()<end){
     String content=Files.readString(log);var matcher=Pattern.compile("Tomcat started on port ([0-9]+)").matcher(content);
     if(matcher.find()){url="http://127.0.0.1:"+matcher.group(1);break;}Thread.sleep(100);
    }
    if(url==null)throw new IllegalStateException("Boot failed: "+log);process=child;base=url;
   }catch(Exception e){if(child!=null)NotificationServerProcess.stop(child);throw e;}finally{Files.deleteIfExists(args);}
  }
  public void close()throws Exception{NotificationServerProcess.stop(process);}
 }
 HttpResponse<String> post(Server server,EnrollmentService.Request r)throws Exception{
  return http.send(HttpRequest.newBuilder(URI.create(server.base+"/api/enrollments")).timeout(Duration.ofSeconds(20))
   .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(r))).build(),HttpResponse.BodyHandlers.ofString());
 }
 @Test void bothRealServersEnforceCreditAndExposeSameStrategy()throws Exception{
  var rows=new ArrayList<Map<String,Object>>();
  for(String strategy:List.of("PESSIMISTIC","REDIS","OPTIMISTIC")){
   String run=UUID.randomUUID().toString();jdbc.update("INSERT INTO experiment_run(run_id) VALUES(?)",run);
   jdbc.update("INSERT INTO student_term(run_id,student_id,term_id,status,max_credits) VALUES(?,1,202602,'ACTIVE',3)",run);
   for(int course=1;course<=2;course++){
    jdbc.update("INSERT INTO offered_course(run_id,id,term_id,subject_id,name,department,grade,credits,capacity) VALUES(?,?,202602,?,'smoke','D0',1,3,100)",run,course,course);
    jdbc.update("INSERT INTO offering_schedule VALUES(?,?,?,?,?)",run,course,course,540,600);
   }
   try(var api=new LockApiSafetyTest.Api();var a=new Server(strategy,api.url(),1);var b=new Server(strategy,api.url(),2)){
    assertNotEquals(a.process.pid(),b.process.pid());
    for(var server:List.of(a,b)){
     var state=json.readTree(http.send(HttpRequest.newBuilder(URI.create(server.base+"/experiment/state")).timeout(Duration.ofSeconds(10)).build(),HttpResponse.BodyHandlers.ofString()).body());
     assertEquals(server.process.pid(),state.get("pid").asLong());assertEquals("LOCK_API_"+strategy,state.get("strategy").asText());
    }
    var executor=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
    try{
     var ra=new EnrollmentService.Request(UUID.randomUUID().toString(),run,1,202602,1);
     var rb=new EnrollmentService.Request(UUID.randomUUID().toString(),run,1,202602,2);
     var fa=executor.submit(()->{start.await();return post(a,ra);});var fb=executor.submit(()->{start.await();return post(b,rb);});start.countDown();
     var responses=List.of(fa.get(25,TimeUnit.SECONDS),fb.get(25,TimeUnit.SECONDS));
     assertEquals(1,responses.stream().filter(r->r.statusCode()==201).count());
     assertEquals(1,responses.stream().filter(r->r.statusCode()==409&&r.body().contains("CREDIT_LIMIT_EXCEEDED")).count());
     var integrity=fixture.integrity(run);assertEquals(1L,integrity.get("confirmed"));assertEquals(0L,integrity.get("creditViolations"));assertEquals(0L,integrity.get("counterMismatches"));
     rows.add(Map.of("strategy",strategy,"pids",List.of(a.process.pid(),b.process.pid()),"codes",responses.stream().map(HttpResponse::statusCode).toList(),"integrity",integrity));
     json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target","lock-api-two-server.json").toFile(),rows);
    }finally{executor.shutdownNow();}
   }
  }
 }
}
