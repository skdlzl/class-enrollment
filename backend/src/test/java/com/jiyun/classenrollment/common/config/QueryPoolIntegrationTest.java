package com.jiyun.classenrollment.common.config;

import com.jiyun.classenrollment.course.api.CourseQueryController;
import com.jiyun.classenrollment.enrollment.application.EnrollmentRedissonFacade;
import com.jiyun.classenrollment.experiment.ExperimentController;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.*;
import org.testcontainers.junit.jupiter.*;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(properties={"enrollment.query-pool.enabled=true","enrollment.query-pool.size=4",
    "spring.datasource.hikari.maximum-pool-size=10","spring.datasource.hikari.connection-timeout=1000",
    "enrollment.course-lock-strategy=PESSIMISTIC","enrollment.queue.enabled=false","experiment.enabled=true"})
class QueryPoolIntegrationTest {
    @Container static MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.4");
    @Container static GenericContainer<?> redis=new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",mysql::getJdbcUrl);r.add("spring.datasource.username",mysql::getUsername);
        r.add("spring.datasource.password",mysql::getPassword);
        r.add("redisson.address",()->"redis://"+redis.getHost()+":"+redis.getMappedPort(6379));
    }
    @Autowired @Qualifier("dataSource") HikariDataSource write;
    @Autowired @Qualifier("catalogDataSource") HikariDataSource read;
    @Autowired JdbcTemplate jdbc;
    @Autowired CourseQueryController courses;
    @Autowired EnrollmentRedissonFacade enrollment;
    @Autowired ExperimentController experiment;

    @Test void catalogueStillWorksWhenAllWriteConnectionsAreHeld() throws Exception {
        assertEquals(6,write.getMaximumPoolSize());assertEquals(4,read.getMaximumPoolSize());
        assertSame(write,jdbc.getDataSource());
        var connections=new ArrayList<Connection>();
        var executor=Executors.newSingleThreadExecutor();
        try {
            for(int i=0;i<6;i++)connections.add(write.getConnection());
            assertEquals(6,write.getHikariPoolMXBean().getActiveConnections());
            var page=executor.submit(()->courses.list(2026,2,"CS",0,20,null,""));
            assertNotNull(page.get(3,TimeUnit.SECONDS));
            assertNotNull(read.getHikariPoolMXBean());
            assertEquals(10,experiment.pool().get("max"));
            assertEquals(true,experiment.pool().get("queryPoolEnabled"));
        } finally {for(var c:connections)c.close();executor.shutdownNow();}
    }
    @Test void guardedWriteCommitsAndCatalogueSeesNewCount() {
        jdbc.update("DELETE FROM enrollments");
        jdbc.update("UPDATE courses SET enrolled_count=0,capacity=100 WHERE id=1");
        jdbc.update("INSERT INTO students(id,student_number,name,status,max_credits) VALUES(90000,'POOL90000','pool','ACTIVE',18)");
        assertNotNull(enrollment.enroll(90000L,1L));
        assertEquals(1,jdbc.queryForObject("SELECT enrolled_count FROM courses WHERE id=1",Integer.class));
        String department=jdbc.queryForObject("SELECT department FROM courses WHERE id=1",String.class);
        int year=jdbc.queryForObject("SELECT academic_year FROM courses WHERE id=1",Integer.class);
        int semester=jdbc.queryForObject("SELECT semester FROM courses WHERE id=1",Integer.class);
        var first=courses.list(year,semester,department,0,100,null,"").items().stream().filter(c->c.id()==1).findFirst().orElseThrow();
        assertEquals(1,first.enrolledCount());
    }
}
