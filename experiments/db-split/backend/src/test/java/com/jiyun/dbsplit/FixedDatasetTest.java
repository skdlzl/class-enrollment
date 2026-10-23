package com.jiyun.dbsplit;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties="experiment.dataset-policy=fixed")
class FixedDatasetTest {
 @Container static final MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.4");
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",mysql::getJdbcUrl);r.add("spring.datasource.username",mysql::getUsername);r.add("spring.datasource.password",mysql::getPassword);
 }
 @Autowired FixtureService fixtures; @Autowired EnrollmentService service; @Autowired JdbcTemplate jdbc;
 @Autowired @Qualifier("academicJdbc") JdbcTemplate academic;
 @Autowired DatabaseConfig.Mode mode; @Autowired Metrics metrics; @Autowired ExperimentController controller;
 @Test void stableDatasetSnapshotAndDatabaseBoundary() throws Exception {
  var created=fixtures.create();String run=(String)created.get("runId");
  assertEquals(run,fixtures.create().get("runId"));
  assertEquals(10000L,fixtures.integrity(run).get("totalCatalogRows"));
  assertEquals(10000L,fixtures.integrity(run).get("totalSubjectRows"));
  assertEquals(500L,fixtures.integrity(run).get("totalStudentRows"));
  // This academic change must NOT alter the already established enrollment-period snapshot.
  academic.update("UPDATE academic_student SET status='INACTIVE',max_credits=3 WHERE run_id=? AND id=1",run);
  long before=acquisitions("academicPool.acquire");
  var request=new EnrollmentService.Request(UUID.randomUUID().toString(),run,1,202602,1);
  assertEquals("CONFIRMED",service.enroll(request).status());
  if(mode!=DatabaseConfig.Mode.A)assertEquals(before,acquisitions("academicPool.acquire"),"Enrollment must not acquire an academic connection");
  assertEquals(18,jdbc.queryForObject("SELECT max_credits FROM student_term WHERE run_id=? AND student_id=1",Integer.class,run));
  assertThrows(IllegalArgumentException.class,()->fixtures.reset(UUID.randomUUID().toString()));
  assertEquals(1L,fixtures.integrity(run).get("confirmed"));
  fixtures.reset(run);
  assertEquals(0L,fixtures.integrity(run).get("confirmed"));
  assertEquals(0L,fixtures.integrity(run).get("counterMismatches"));
  assertEquals(0L,fixtures.integrity(run).get("creditViolations"));
  assertEquals(10000L,fixtures.integrity(run).get("totalCatalogRows"));
  assertEquals(run,fixtures.create().get("runId"));
  assertEquals(mode==DatabaseConfig.Mode.A?1:2,metrics.poolState().size());
  var state=controller.state();var databases=(Map<?,?>)state.get("databases");
  Object academicUuid=((Map<?,?>)databases.get("academic")).get("serverUuid");
  Object enrollmentUuid=((Map<?,?>)databases.get("enrollment")).get("serverUuid");
  if(mode==DatabaseConfig.Mode.B){
   assertNotEquals(academicUuid,enrollmentUuid);
   assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='subject'",Integer.class));
   assertEquals(0,academic.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='enrollment'",Integer.class));
  }else assertEquals(academicUuid,enrollmentUuid);
  if(mode!=DatabaseConfig.Mode.A){
   var held=new ArrayList<java.sql.Connection>();
   try{
    for(int i=0;i<10;i++)held.add(jdbc.getDataSource().getConnection());
    assertEquals(1,academic.queryForObject("SELECT 1",Integer.class),"Academic reads must work while every enrollment connection is occupied");
   }finally{for(var connection:held)connection.close();}
  }

 }
 long acquisitions(String name){var phases=(Map<?,?>)metrics.snapshot().get("phases");var value=(Map<?,?>)phases.get(name);return value==null?0:((Number)value.get("count")).longValue();}
}
