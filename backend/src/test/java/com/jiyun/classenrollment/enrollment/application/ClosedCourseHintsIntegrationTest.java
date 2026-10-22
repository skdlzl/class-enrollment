package com.jiyun.classenrollment.enrollment.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(properties={"enrollment.closed-course-hint.enabled=true","enrollment.closed-course-hint.ttl-ms=60000",
    "enrollment.course-lock-strategy=PESSIMISTIC","enrollment.queue.enabled=false"})
class ClosedCourseHintsIntegrationTest {
    @Container static MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.4");
    @Container static GenericContainer<?> redisServer=new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",mysql::getJdbcUrl);r.add("spring.datasource.username",mysql::getUsername);
        r.add("spring.datasource.password",mysql::getPassword);
        r.add("redisson.address",()->"redis://"+redisServer.getHost()+":"+redisServer.getMappedPort(6379));
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired EnrollmentRedissonFacade facade;
    @Autowired EnrollmentService service;
    @Autowired ClosedCourseHints hints;
    @Autowired RedissonClient redis;
    @Autowired PlatformTransactionManager manager;
    @BeforeEach void setup(){
        hints.clear(1L);jdbc.update("DELETE FROM enrollments");
        jdbc.update("UPDATE courses SET enrolled_count=0,capacity=1 WHERE id=1");
        jdbc.update("INSERT IGNORE INTO students(id,student_number,name,status,max_credits) VALUES(99001,'HINT1','one','ACTIVE',18),(99002,'HINT2','two','ACTIVE',18)");
    }
    @Test void committedHintIsSharedAndStaleHintCannotRejectAfterReopening(){
        facade.enroll(99001L,1L);
        RedissonClient other=Redisson.create(redis.getConfig());
        try {
            var otherHints=new ClosedCourseHints(other,jdbc,true,60000);
            var error=assertThrows(com.jiyun.classenrollment.common.error.EnrollmentException.class,()->otherHints.rejectIfClosed(1L));
            assertEquals("COURSE_FULL",error.getCode());
            // Simulate an atomic cancellation, including a delayed stale mark after clear.
            new TransactionTemplate(manager).executeWithoutResult(s->{jdbc.update("DELETE FROM enrollments WHERE course_id=1");jdbc.update("UPDATE courses SET enrolled_count=0 WHERE id=1");});
            hints.clear(1L);otherHints.mark(1L);
            assertDoesNotThrow(()->facade.enroll(99002L,1L));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM enrollments WHERE course_id=1",Integer.class));
        } finally {other.shutdown();}
    }
    @Test void rolledBackLastSeatDoesNotPublishClosedHint(){
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).executeWithoutResult(s->{
            service.completeEnrollmentWithPessimisticLock(99001L,1L);
            throw new IllegalStateException("rollback after save");
        }));
        assertFalse(redis.getBucket("enrollment:closed-hint:v1:1").isExists());
        assertEquals(0,jdbc.queryForObject("SELECT enrolled_count FROM courses WHERE id=1",Integer.class));
        assertDoesNotThrow(()->facade.enroll(99002L,1L));
    }
}
