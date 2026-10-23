package com.jiyun.classenrollment.course.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiyun.classenrollment.course.api.CourseQueryController;
import com.jiyun.classenrollment.common.error.EnrollmentException;
import com.jiyun.classenrollment.enrollment.application.EnrollmentRedissonFacade;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(properties={"enrollment.search-cache.enabled=true","enrollment.search-cache.ttl-ms=5000",
    "enrollment.course-lock-strategy=DB_ONLY","enrollment.queue.enabled=false"})
class CourseSearchCacheIntegrationTest {
    @Container static MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.4");
    @Container static GenericContainer<?> redisServer=new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",mysql::getJdbcUrl);r.add("spring.datasource.username",mysql::getUsername);
        r.add("spring.datasource.password",mysql::getPassword);
        r.add("redisson.address",()->"redis://"+redisServer.getHost()+":"+redisServer.getMappedPort(6379));
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired CourseQueryController controller;
    @Autowired CourseSearchCache cache;
    @Autowired EnrollmentRedissonFacade facade;
    @Autowired RedissonClient redis;
    @Autowired ObjectMapper mapper;
    @Test void staleSearchAvailabilityCannotOversubscribeAndSecondClientSharesSnapshot(){
        jdbc.update("DELETE FROM enrollments");
        jdbc.update("UPDATE courses SET enrolled_count=0,capacity=1,department='CACHE_TEST',academic_year=2026,semester=2 WHERE id=1");
        jdbc.update("INSERT IGNORE INTO students(id,student_number,name,status,max_credits) VALUES(99001,'CACHE1','one','ACTIVE',18),(99002,'CACHE2','two','ACTIVE',18)");
        var before=controller.list(2026,2,"CACHE_TEST",0,20,null,"");
        assertEquals(0,before.items().get(0).enrolledCount());
        facade.enroll(99001L,1L);
        // A client can submit using the old response even after it expires. DB must reject safely.
        assertTrue(before.items().get(0).enrolledCount()<before.items().get(0).capacity());
        var rejected=assertThrows(EnrollmentException.class,()->facade.enroll(99002L,1L));
        assertEquals("COURSE_FULL",rejected.getCode());
        assertEquals(1,jdbc.queryForObject("SELECT enrolled_count FROM courses WHERE id=1",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM enrollments WHERE course_id=1",Integer.class));
        // Separate Redisson connections emulate two application instances sharing a search entry.
        var page=new CourseQueryController.Page(List.of(),false,null);
        cache.get("cross-server-probe",()->page);
        RedissonClient other=Redisson.create(redis.getConfig());
        try {
            var remote=new CourseSearchCache(other,mapper,true,5000);
            assertEquals(page,remote.get("cross-server-probe",()->{throw new AssertionError("Unexpected database load");}));
            assertEquals(1L,remote.snapshot().get("hits"));
        } finally {other.shutdown();}
    }
}
