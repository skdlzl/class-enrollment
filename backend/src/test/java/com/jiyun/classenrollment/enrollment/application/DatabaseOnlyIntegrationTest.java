package com.jiyun.classenrollment.enrollment.application;

import com.jiyun.classenrollment.common.error.EnrollmentException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
@SpringBootTest(properties={"enrollment.course-lock-strategy=DB_ONLY","enrollment.local-gate-enabled=false","enrollment.queue.enabled=false","enrollment.capacity-precheck=true"})
class DatabaseOnlyIntegrationTest {
    @Container @ServiceConnection static MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.4");
    // Application still configures Redis for other features; tested enrollment uses mock clients below.
    @Container static GenericContainer<?> redis=new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("redisson.address",()->"redis://"+redis.getHost()+":"+redis.getMappedPort(6379));
    }
    @Autowired EnrollmentService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    RedissonClient clientA,clientB;
    EnrollmentRedissonFacade serverA,serverB;
    @BeforeEach void setup(){
        jdbc.update("DELETE FROM enrollments");
        jdbc.update("DELETE FROM course_schedules WHERE course_id IN (1,2)");
        jdbc.update("UPDATE courses SET capacity=5,enrolled_count=0,credits=3");
        jdbc.update("DELETE FROM students WHERE id BETWEEN 9000 AND 9019");
        for(int i=9000;i<9020;i++)jdbc.update("INSERT INTO students(id,student_number,name,status,max_credits) VALUES(?,?,?,'ACTIVE',18)",i,"DBONLY"+i,"Student"+i);
        clientA=mock(RedissonClient.class);clientB=mock(RedissonClient.class);
        serverA=new EnrollmentRedissonFacade(service,clientA,CourseLockStrategy.DB_ONLY);
        serverB=new EnrollmentRedissonFacade(service,clientB,CourseLockStrategy.DB_ONLY);
    }
    List<String> concurrent(List<long[]> requests) throws Exception {
        var pool=Executors.newFixedThreadPool(requests.size());
        var ready=new CountDownLatch(requests.size());var start=new CountDownLatch(1);
        try {
            List<Future<String>> futures=new ArrayList<>();
            for(int i=0;i<requests.size();i++){
                var ids=requests.get(i);var target=i%2==0?serverA:serverB;
                futures.add(pool.submit(()->{ready.countDown();start.await();try {target.enroll(ids[0],ids[1]);return "SUCCESS";}catch(EnrollmentException e){return e.getCode();}}));
            }
            boolean allReady=ready.await(10,TimeUnit.SECONDS);start.countDown();assertTrue(allReady);
            List<String> results=new ArrayList<>();for(var f:futures)results.add(f.get(30,TimeUnit.SECONDS));
            verifyNoInteractions(clientA,clientB);return results;
        }finally{start.countDown();pool.shutdownNow();}
    }
    @Test void sameStudentCannotExceedCreditLimitAcrossDifferentCourses() throws Exception {
        jdbc.update("UPDATE students SET max_credits=3 WHERE id=9000");
        var results=concurrent(List.of(new long[]{9000,1},new long[]{9000,2}));
        assertEquals(1,Collections.frequency(results,"SUCCESS"));assertEquals(1,Collections.frequency(results,"CREDIT_LIMIT_EXCEEDED"));
        assertEquals(3,jdbc.queryForObject("SELECT SUM(c.credits) FROM enrollments e JOIN courses c ON c.id=e.course_id WHERE e.student_id=9000",Integer.class));
    }
    @Test void sameStudentCannotEnrollOverlappingSchedules() throws Exception {
        jdbc.update("INSERT INTO course_schedules(course_id,day_of_week,start_time,end_time) VALUES(1,'MONDAY','09:00:00','10:00:00'),(2,'MONDAY','09:30:00','10:30:00')");
        var results=concurrent(List.of(new long[]{9000,1},new long[]{9000,2}));
        assertEquals(1,Collections.frequency(results,"SUCCESS"));assertEquals(1,Collections.frequency(results,"SCHEDULE_CONFLICT"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM enrollments WHERE student_id=9000",Integer.class));
    }
    @Test void independentFacadesCannotExceedCourseCapacity() throws Exception {
        List<long[]> requests=new ArrayList<>();for(int i=9000;i<9020;i++)requests.add(new long[]{i,1});
        var results=concurrent(requests);
        assertEquals(5,Collections.frequency(results,"SUCCESS"));assertEquals(15,Collections.frequency(results,"COURSE_FULL"));
        assertEquals(5,jdbc.queryForObject("SELECT enrolled_count FROM courses WHERE id=1",Integer.class));
        assertEquals(5,jdbc.queryForObject("SELECT COUNT(*) FROM enrollments WHERE course_id=1",Integer.class));
    }
    @Test void rollbackReleasesBothLocksAndUndoesEnrollment(){
        var tx=new TransactionTemplate(manager);
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(status->{service.enrollWithDatabaseLocks(9000L,1L);throw new IllegalStateException("injected");}));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM enrollments",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT enrolled_count FROM courses WHERE id=1",Integer.class));
        assertNotNull(serverB.enroll(9000L,1L));verifyNoInteractions(clientB);
    }
}
