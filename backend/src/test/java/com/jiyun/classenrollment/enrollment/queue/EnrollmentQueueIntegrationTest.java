package com.jiyun.classenrollment.enrollment.queue;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.redisson.Redisson;
import org.redisson.config.Config;
import org.redisson.api.RedissonClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.concurrent.*;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties={"enrollment.queue.enabled=true","enrollment.queue.scheduler-enabled=false"})
class EnrollmentQueueIntegrationTest {
    @Container @ServiceConnection static MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.4");
    @Container static GenericContainer<?> redisContainer=new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    static String address(){return "redis://"+redisContainer.getHost()+":"+redisContainer.getMappedPort(6379);}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("redisson.address",EnrollmentQueueIntegrationTest::address);}
    @Autowired EnrollmentRequestStore store;
    @Autowired EnrollmentQueueWorker worker;
    @Autowired QueuedEnrollmentTransaction transaction;
    @Autowired JdbcTemplate jdbc;
    @Autowired RedissonClient redis;
    @Autowired MockMvc mvc;
    @BeforeEach void reset(){
        redis.getKeys().flushdb();
        jdbc.update("DELETE FROM enrollment_requests"); jdbc.update("DELETE FROM enrollments");
        jdbc.update("UPDATE courses SET capacity=1,enrolled_count=0");
        jdbc.update("UPDATE students SET max_credits=18");
    }
    @Test void acceptedIsNotConfirmedAndLegacyEndpointCannotBypassQueue() throws Exception {
        mvc.perform(post("/api/enrollment-requests").contentType("application/json").content("{\"studentId\":1,\"courseId\":1}"))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("QUEUED"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM enrollments",Integer.class));
        mvc.perform(post("/api/enrollments").contentType("application/json").content("{\"studentId\":1,\"courseId\":1}"))
            .andExpect(status().isConflict());
        mvc.perform(get("/api/enrollment-requests/options")).andExpect(status().isOk())
            .andExpect(jsonPath("$.schedules[0].start").value("09:00"));
    }
    @Test void concurrentRepeatedPostsReturnOneDurableReceipt() throws Exception {
        var pool=Executors.newFixedThreadPool(8);
        try {
            var tasks=new ArrayList<Future<String>>();
            for(int i=0;i<20;i++)tasks.add(pool.submit(()->store.submit(1,1).requestId()));
            String id=tasks.get(0).get();
            for(var task:tasks)assertEquals(id,task.get());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM enrollment_requests",Integer.class));
        } finally{pool.shutdownNow();}
    }
    @Test void lostRedisQueueIsRebuiltAndRepeatedDeliveryDoesNotDuplicateWrite(){
        var ticket=store.submit(1,1);
        redis.getKeys().flushdb();
        worker.recover();worker.tick();worker.process(ticket.requestId());
        assertEquals("CONFIRMED",store.get(ticket.requestId()).status());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM enrollments",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT enrolled_count FROM courses WHERE id=1",Integer.class));
    }
    @Test void crashedProcessingReceiptCanBeRecovered(){
        var ticket=store.submit(1,1);assertTrue(store.start(ticket.requestId()));
        jdbc.update("UPDATE enrollment_requests SET next_attempt_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6))");
        worker.recover();worker.tick();
        assertEquals("CONFIRMED",store.get(ticket.requestId()).status());
    }
    @Test void fullCourseIsBusinessRejectionAndDoesNotChangeCount(){
        var first=store.submit(1,1);worker.process(first.requestId());
        var second=store.submit(2,1);worker.process(second.requestId());
        assertEquals("REJECTED",store.get(second.requestId()).status());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM enrollments",Integer.class));
    }
    @Test void staleOwnersWithoutRedisStillCannotOversellOrExceedStudentCredits() throws Exception {
        var a=store.submit(1,1);var b=store.submit(2,1);
        store.start(a.requestId());store.start(b.requestId());
        var pool=Executors.newFixedThreadPool(2);
        try {
            var start=new CountDownLatch(1);
            var one=pool.submit(()->{start.await();try{transaction.confirm(a);}catch(com.jiyun.classenrollment.common.error.EnrollmentException ignored){}return null;});
            var two=pool.submit(()->{start.await();try{transaction.confirm(b);}catch(com.jiyun.classenrollment.common.error.EnrollmentException ignored){}return null;});
            start.countDown();one.get(15,TimeUnit.SECONDS);two.get(15,TimeUnit.SECONDS);
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM enrollments",Integer.class));
        }finally{pool.shutdownNow();}
        reset();jdbc.update("UPDATE students SET max_credits=3 WHERE id=1");
        var c=store.submit(1,1);var d=store.submit(1,2);
        worker.process(c.requestId());worker.process(d.requestId());
        assertEquals("REJECTED",store.get(d.requestId()).status());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM enrollments",Integer.class));
    }
    @Test void unavailableGlobalSlotsPreventDatabaseExecutionAcrossClients(){
        var config=new Config();config.useSingleServer().setAddress(address());
        var other=Redisson.create(config);
        try {
            var ticket=store.submit(1,1);worker.recover();
            for(int i=0;i<4;i++)other.getLock("enrollment:worker-slot:"+i).lock();
            worker.tick();
            assertEquals("QUEUED",store.get(ticket.requestId()).status());
            for(int i=0;i<4;i++)other.getLock("enrollment:worker-slot:"+i).unlock();
            worker.tick();assertEquals("CONFIRMED",store.get(ticket.requestId()).status());
        }finally{other.shutdown();}
    }
    @Test void retryBudgetAndExplicitRetry(){
        var ticket=store.submit(1,1);store.start(ticket.requestId());store.retryLater(ticket.requestId(),1);
        assertEquals("FAILED",store.get(ticket.requestId()).status());
        store.retry(ticket.requestId());worker.process(ticket.requestId());
        assertEquals("CONFIRMED",store.get(ticket.requestId()).status());
    }

    @Test void contendedCourseIsRequeuedWithoutWaitingForRecoveryScan() throws Exception {
        var config=new Config();config.useSingleServer().setAddress(address());
        var other=Redisson.create(config);
        try {
            var ticket=store.submit(1,1);
            other.getLock("lock:course:1").lock();
            worker.process(ticket.requestId());
            assertEquals("QUEUED",store.get(ticket.requestId()).status());
            assertEquals(0,store.get(ticket.requestId()).attempts());
            other.getLock("lock:course:1").unlock();
            // Exercise the Redis retry eligibility without ever invoking recover().
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(!store.get(ticket.requestId()).terminal() && System.nanoTime()<deadline) {
                worker.tick();Thread.sleep(20);
            }
            assertEquals("CONFIRMED",store.get(ticket.requestId()).status());
        } finally {other.shutdown();}
    }
    @Test void fullCourseRejectsWithoutAcquiringItsRedisLock() {
        var first=store.submit(1,1);worker.process(first.requestId());
        var config=new Config();config.useSingleServer().setAddress(address());
        var other=Redisson.create(config);
        try {
            other.getLock("lock:course:1").lock();
            var second=store.submit(2,1);worker.process(second.requestId());
            assertEquals("REJECTED",store.get(second.requestId()).status());
            other.getLock("lock:course:1").unlock();
        }finally{other.shutdown();}
    }
    @Test void fullCourseDoesNotRejectAnAlreadyEnrolledStudent() {
        var first=store.submit(1,1);worker.process(first.requestId());
        // Reconcile an enrollment predating the new receipt.
        jdbc.update("DELETE FROM enrollment_requests WHERE request_id=?",first.requestId());
        var again=store.submit(1,1);worker.process(again.requestId());
        assertEquals("CONFIRMED",store.get(again.requestId()).status());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM enrollments",Integer.class));
    }
    @Test void failedFinalUpdateRollsBackEnrollmentAndCount(){
        var ticket=store.submit(1,1);store.start(ticket.requestId());
        // A DB rejection at the final receipt update must also roll back enrollment.
        jdbc.execute("ALTER TABLE enrollment_requests ADD CONSTRAINT chk_test_reject_confirm CHECK(status <> 'CONFIRMED')");
        try {
            assertThrows(RuntimeException.class,()->transaction.confirm(ticket));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM enrollments",Integer.class));
            assertEquals(0,jdbc.queryForObject("SELECT enrolled_count FROM courses WHERE id=1",Integer.class));
        }finally{jdbc.execute("ALTER TABLE enrollment_requests DROP CHECK chk_test_reject_confirm");}
        worker.process(ticket.requestId()); // Not yet due, recovery will retry after lease.
    }
}
