package com.jiyun.classenrollment.enrollment.queue;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties={"enrollment.queue.enabled=true","enrollment.queue.scheduler-enabled=false"})
class RedisIngressIntegrationTest {
    @Container @ServiceConnection static MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.4");
    @Container static GenericContainer<?> redisContainer=new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    static String address(){return "redis://"+redisContainer.getHost()+":"+redisContainer.getMappedPort(6379);}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("redisson.address",RedisIngressIntegrationTest::address);}
    @Autowired RedisIngressStore store;
    @Autowired RedisIngressWorker worker;
    @Autowired IngressProcessor processor;
    @MockitoSpyBean JdbcTemplate jdbc;
    @Autowired RedissonClient redis;
    @Autowired MockMvc mvc;
    @BeforeEach void reset(){
        redis.getKeys().flushdb();
        jdbc.update("DELETE FROM enrollment_ingress_results");
        jdbc.update("DELETE FROM enrollment_requests");jdbc.update("DELETE FROM enrollments");
        jdbc.update("UPDATE courses SET capacity=1,enrolled_count=0");
        jdbc.update("UPDATE students SET max_credits=18");
        clearInvocations(jdbc);
    }
    @Test void receiptAndStatusPollingDoNotUseDatabase() throws Exception {
        var t=store.submit(1,1);
        assertEquals("QUEUED",store.get(t.requestId()).status());
        mvc.perform(get("/api/enrollment-requests/"+t.requestId())).andExpect(status().isOk());
        mvc.perform(get("/api/enrollment-requests/snapshot?studentId=1")).andExpect(status().isOk());
        mvc.perform(post("/api/enrollment-requests").contentType("application/json").content("{\"studentId\":2,\"courseId\":1}"))
            .andExpect(status().isAccepted());
        verifyNoInteractions(jdbc);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM enrollment_ingress_results",Integer.class));
    }
    @Test void duplicateConcurrentReceiptsHaveOneIdAndQueueEntry() throws Exception {
        var pool=Executors.newFixedThreadPool(8);
        try{
            var futures=new ArrayList<Future<String>>();
            for(int i=0;i<20;i++)futures.add(pool.submit(()->store.submit(1,1).requestId()));
            String id=futures.get(0).get();for(var f:futures)assertEquals(id,f.get());
            assertEquals(1,store.pending());
        }finally{pool.shutdownNow();}
    }
    @Test void workerCommitsThenPublishesResult(){
        var t=store.submit(1,1);worker.tick();
        assertEquals("CONFIRMED",store.get(t.requestId()).status());
        assertEquals(1,jdbc.queryForObject("SELECT enrolled_count FROM courses WHERE id=1",Integer.class));
        assertEquals(0,store.pending());
    }
    @Test void lostAckReplaysCommittedResultWithoutDuplicateWrite(){
        var t=store.submit(1,1);var claim=store.claim(30000);
        processor.process(claim.ticket()); // Simulate crash after DB commit, before Redis ACK.
        redis.getScoredSortedSet("enrollment:{ingress}:ready",org.redisson.client.codec.StringCodec.INSTANCE).add(0,t.requestId());
        worker.tick();
        assertEquals("CONFIRMED",store.get(t.requestId()).status());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM enrollments",Integer.class));
    }
    @Test void expiredOwnerCannotOverwriteNewClaim(){
        var t=store.submit(1,1);var old=store.claim(30000);
        redis.getScoredSortedSet("enrollment:{ingress}:ready",org.redisson.client.codec.StringCodec.INSTANCE).add(0,t.requestId());
        var current=store.claim(30000);
        assertFalse(store.finish(old,t));
        assertTrue(store.finish(current,processor.process(current.ticket())));
        assertEquals("CONFIRMED",store.get(t.requestId()).status());
    }
    @Test void independentWorkersCannotOversell() throws Exception {
        var a=store.submit(1,1);var b=store.submit(2,1);
        Config config=new Config();config.useSingleServer().setAddress(address());var other=Redisson.create(config);
        var second=new RedisIngressWorker(store,processor,other);var pool=Executors.newFixedThreadPool(2);
        try{
            var x=pool.submit(worker::tick);var y=pool.submit(second::tick);x.get();y.get();
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM enrollments",Integer.class));
            assertEquals(1,java.util.stream.Stream.of(a,b).filter(t->store.get(t.requestId()).status().equals("CONFIRMED")).count());
            assertEquals(1,java.util.stream.Stream.of(a,b).filter(t->store.get(t.requestId()).status().equals("REJECTED")).count());
        }finally{pool.shutdownNow();other.shutdown();}
    }
    @Test void sameStudentConcurrentCoursesCannotExceedCredits() throws Exception {
        jdbc.update("UPDATE courses SET credits=3,capacity=10");
        jdbc.update("UPDATE students SET max_credits=3 WHERE id=1");
        var a=store.submit(1,1);var b=store.submit(1,2);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var start=new CountDownLatch(1);
            var x=pool.submit(()->{start.await();return processor.process(a);});
            var y=pool.submit(()->{start.await();return processor.process(b);});
            start.countDown();var ra=x.get();var rb=y.get();
            assertEquals(1,java.util.stream.Stream.of(ra,rb).filter(t->t.status().equals("CONFIRMED")).count());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM enrollments WHERE student_id=1",Integer.class));
        } finally {pool.shutdownNow();}
    }
    @Test void receiptUpdateFailureRollsBackEnrollmentAndCounter(){
        var t=store.submit(1,1);
        jdbc.execute("ALTER TABLE enrollment_ingress_results ADD CONSTRAINT no_confirm CHECK (status <> 'CONFIRMED')");
        try {
            assertThrows(Exception.class,()->processor.process(t));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM enrollments",Integer.class));
            assertEquals(0,jdbc.queryForObject("SELECT enrolled_count FROM courses WHERE id=1",Integer.class));
        } finally {jdbc.execute("ALTER TABLE enrollment_ingress_results DROP CHECK no_confirm");}
    }
    @Test void invalidIdIsRejectedAsynchronously(){
        var t=store.submit(999999,1);worker.tick();
        assertEquals("REJECTED",store.get(t.requestId()).status());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM enrollments",Integer.class));
    }
    @Test void heldGlobalSlotsPreventDbWork(){
        var t=store.submit(1,1);Config config=new Config();config.useSingleServer().setAddress(address());var other=Redisson.create(config);
        try{
            other.getLock("enrollment:{ingress}:worker:0").lock();other.getLock("enrollment:{ingress}:worker:1").lock();
            clearInvocations(jdbc);assertFalse(worker.tick());verifyNoInteractions(jdbc);
            assertEquals("QUEUED",store.get(t.requestId()).status());
        }finally{other.getLock("enrollment:{ingress}:worker:0").unlock();other.getLock("enrollment:{ingress}:worker:1").unlock();other.shutdown();}
    }
}
