package com.jiyun.dbsplit;
import org.springframework.test.context.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
@TestPropertySource(properties="experiment.mode=B")
class SplitDatabaseSafetyTest extends EnrollmentSafetyTest {
 @Container static final MySQLContainer<?> academicMysql=new MySQLContainer<>("mysql:8.4");
 @DynamicPropertySource static void academicProperties(DynamicPropertyRegistry r){
  r.add("experiment.academic.url",academicMysql::getJdbcUrl);
  r.add("experiment.academic.username",academicMysql::getUsername);
  r.add("experiment.academic.password",academicMysql::getPassword);
 }
}
