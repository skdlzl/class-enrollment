package com.jiyun.dbsplit;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class EnrollmentSafetyTest {
 @Container static final MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.4");
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",mysql::getJdbcUrl);r.add("spring.datasource.username",mysql::getUsername);r.add("spring.datasource.password",mysql::getPassword);
 }
 @Autowired EnrollmentService service; @Autowired JdbcTemplate jdbc;
 @Autowired PlatformTransactionManager tx; @Autowired Metrics metrics; @Autowired FixtureService fixture;
 @Autowired TestRestTemplate http; @LocalServerPort int port;
 String run;
 @BeforeEach void seed(){
  run=UUID.randomUUID().toString();jdbc.update("INSERT INTO experiment_run(run_id) VALUES(?)",run);
  for(int i=1;i<=20;i++)jdbc.update("INSERT INTO student_term(run_id,student_id,term_id,status,max_credits) VALUES(?,?,202602,'ACTIVE',18)",run,i);
  for(int i=1;i<=5;i++){
   jdbc.update("INSERT INTO offered_course(run_id,id,term_id,subject_id,name,department,grade,credits,capacity) VALUES(?,?,202602,?,'수강실습','D0',1,3,3)",run,i,i);
   jdbc.update("INSERT INTO offering_schedule VALUES(?,?,?,?,?)",run,i,i,540,600);
  }
 }
 EnrollmentService.Request request(long student,long course){return new EnrollmentService.Request(UUID.randomUUID().toString(),run,student,202602,course);}
 List<String> concurrent(List<EnrollmentService.Request> requests)throws Exception{
  var executor=Executors.newFixedThreadPool(requests.size());var start=new CountDownLatch(1);
  try{
   var futures=new ArrayList<Future<String>>();
   for(var r:requests)futures.add(executor.submit(()->{start.await();try{return service.enroll(r).status();}catch(EnrollmentService.Rejected e){return e.code;}}));
   start.countDown();var result=new ArrayList<String>();for(var f:futures)result.add(f.get(30,TimeUnit.SECONDS));return result;
  }finally{executor.shutdownNow();}
 }
 void consistent(){var d=fixture.integrity(run);assertEquals(0L,d.get("counterMismatches"));assertEquals(0L,d.get("creditViolations"));assertEquals(0L,d.get("scheduleViolations"));}
 @Test void concurrentCapacity()throws Exception{
  var requests=new ArrayList<EnrollmentService.Request>();for(int i=1;i<=20;i++)requests.add(request(i,1));
  var outcomes=concurrent(requests);assertEquals(3,Collections.frequency(outcomes,"CONFIRMED"));assertEquals(17,Collections.frequency(outcomes,"COURSE_FULL"));consistent();
 }
 @Test void sameStudentCreditRace()throws Exception{
  jdbc.update("UPDATE student_term SET max_credits=3 WHERE run_id=? AND student_id=1",run);
  var outcomes=concurrent(List.of(request(1,1),request(1,2)));
  assertEquals(1,Collections.frequency(outcomes,"CONFIRMED"));assertEquals(1,Collections.frequency(outcomes,"CREDIT_LIMIT_EXCEEDED"));consistent();
 }
 @Test void sameStudentScheduleRace()throws Exception{
  jdbc.update("UPDATE offering_schedule SET day_no=1 WHERE run_id=? AND course_id=2",run);
  var outcomes=concurrent(List.of(request(1,1),request(1,2)));
  assertEquals(1,Collections.frequency(outcomes,"CONFIRMED"));assertEquals(1,Collections.frequency(outcomes,"SCHEDULE_CONFLICT"));consistent();
 }
 @Test void duplicateRequestAfterLostResponseIsIdempotent()throws Exception{
  var r=request(1,1);assertEquals(List.of("CONFIRMED","CONFIRMED"),concurrent(List.of(r,r)));
  assertEquals(1L,fixture.integrity(run).get("confirmed"));consistent();
 }
 @Test void sameKeyCannotChangeIdentity(){
  var r=request(1,1);service.enroll(r);
  var other=new EnrollmentService.Request(r.requestId(),run,1,202602,2);
  assertEquals("REQUEST_ID_CONFLICT",assertThrows(EnrollmentService.Rejected.class,()->service.enroll(other)).code);consistent();
 }
 @Test void rollbackRestoresCountersAndReleasesLocks(){
  var r=request(1,1);var template=new TransactionTemplate(tx);
  assertThrows(IllegalStateException.class,()->template.execute(s->{service.enroll(r);throw new IllegalStateException("simulated write failure");}));
  assertEquals(0L,fixture.integrity(run).get("confirmed"));consistent();assertEquals("CONFIRMED",service.enroll(r).status());consistent();
 }
 @Test void cancellationIsIdempotentAndReturnsCapacity(){
  var r=request(1,1);service.enroll(r);service.cancel(r);service.cancel(r);
  assertEquals(0L,fixture.integrity(run).get("confirmed"));assertEquals("CANCELLED",service.enroll(r).status());consistent();
 }
 @Test void apiAndPoolInstrumentation(){
  var response=http.postForEntity("http://127.0.0.1:"+port+"/api/enrollments",request(1,1),Map.class);
  assertEquals(201,response.getStatusCode().value());assertEquals("CONFIRMED",response.getBody().get("status"));
  var phases=(Map<?,?>)metrics.snapshot().get("phases");
  for(String name:List.of("sharedPool.acquire","sharedPool.usage","courseLock.sql","courseLock.holdUntilCompletion","enrollment.transactionProxy")){
   assertTrue(phases.containsKey(name),name);assertTrue(((Number)((Map<?,?>)phases.get(name)).get("count")).longValue()>0,name);
  }
 }
 @Test void fullFixtureSearchAndPagination(){
  var seeded=fixture.create();String id=(String)seeded.get("runId");
  var first=http.getForObject("http://127.0.0.1:"+port+"/api/offerings?runId="+id+"&department=D0&grade=1",List.class);
  assertEquals(50,first.size());var seen=new HashSet<Integer>();for(Object row:first)seen.add((Integer)((Map<?,?>)row).get("id"));
  int cursor=seen.stream().mapToInt(x->x).max().orElseThrow();
  var second=http.getForObject("http://127.0.0.1:"+port+"/api/offerings?runId="+id+"&department=D0&grade=1&afterId="+cursor,List.class);
  for(Object row:second)assertFalse(seen.contains((Integer)((Map<?,?>)row).get("id")));
  var search=http.getForObject("http://127.0.0.1:"+port+"/api/offerings?runId="+id+"&keyword={keyword}",List.class,"수강실습");assertEquals(5,search.size());
 }
}
