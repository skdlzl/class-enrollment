package com.jiyun.classenrollment.enrollment.v2;

import com.jiyun.classenrollment.common.error.EnrollmentException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
@SpringBootTest(properties="enrollment.exchange-recovery=false")
class ConcurrencyIntegrationTest {
    @Container @ServiceConnection static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");
    @Container static GenericContainer<?> redis = new GenericContainer<>("redis:7.4").withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }
    @Autowired JdbcTemplate db;
    @Autowired StringRedisTemplate strings;
    @Autowired PlatformTransactionManager manager;
    @Autowired MeterRegistry metrics;
    EligibilityClient external;
    RedisGate gate;
    @BeforeEach void seed() {
        external = mock(EligibilityClient.class);
        gate = new RedisGate(strings, 3000);
        db.update("DELETE FROM exchange_requests");
        db.update("DELETE FROM enrollment_requests");
        db.update("DELETE FROM enrollments");
        db.update("UPDATE courses SET enrolled_count=0,version=0,capacity=10");
        db.update("UPDATE students SET version=0,max_credits=18");
        for (int i=100; i<200; i++)
            db.update("INSERT IGNORE INTO students(id,student_number,name,status,max_credits) VALUES (?,?,'test','ACTIVE',18)",i,"T"+i);
        db.update("DELETE FROM course_schedules WHERE course_id=4");
        db.update("INSERT INTO course_schedules(course_id,day_of_week,start_time,end_time) VALUES (4,'MONDAY','09:30','11:00')");
        strings.delete("enrollment:course:1");
    }
    EnrollmentCoordinator service(EnrollmentCoordinator.Strategy strategy) {
        return new EnrollmentCoordinator(db,gate,external,manager,metrics,strategy,10000);
    }
    List<String> parallel(int n, java.util.function.IntFunction<String> work) throws Exception {
        var pool=Executors.newFixedThreadPool(Math.min(n,32));
        var start=new CountDownLatch(1);
        try {
            List<Future<String>> futures=new ArrayList<>();
            for(int i=0;i<n;i++) { final int k=i; futures.add(pool.submit(() -> {
                start.await();
                try { return work.apply(k); }
                catch(EnrollmentException e) { return e.getCode(); }
            })); }
            start.countDown();
            List<String> result=new ArrayList<>();
            for(var f:futures) result.add(f.get(60,TimeUnit.SECONDS));
            return result;
        } finally { pool.shutdownNow(); }
    }
    @ParameterizedTest @EnumSource(EnrollmentCoordinator.Strategy.class)
    void capacityAndIdempotencyRemainConsistent(EnrollmentCoordinator.Strategy strategy) throws Exception {
        var service=service(strategy);
        var outcomes=parallel(100,i -> { service.enroll(100+i,1,"load-"+i); return "OK"; });
        assertEquals(10, Collections.frequency(outcomes,"OK"));
        assertEquals(10, db.queryForObject("SELECT COUNT(*) FROM enrollments",Integer.class));
        assertEquals(10, db.queryForObject("SELECT enrolled_count FROM courses WHERE id=1",Integer.class));
        assertTrue(outcomes.stream().allMatch(x -> Set.of("OK","COURSE_FULL","RETRY_EXHAUSTED").contains(x)),outcomes.toString());
    }
    @ParameterizedTest @EnumSource(EnrollmentCoordinator.Strategy.class)
    void concurrentIdenticalRequestsReturnSameResult(EnrollmentCoordinator.Strategy strategy) throws Exception {
        var service=service(strategy);
        var results=parallel(32,i -> Long.toString(service.enroll(1,1,"same").enrollmentId()));
        assertEquals(1,new HashSet<>(results).size());
        assertTrue(results.get(0).matches("[0-9]+"),results.toString());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM enrollments",Integer.class));
        assertEquals("IDEMPOTENCY_KEY_REUSED",assertThrows(EnrollmentException.class,()->service.enroll(1,2,"same")).getCode());
        assertEquals("DUPLICATE_ENROLLMENT",assertThrows(EnrollmentException.class,()->service.enroll(1,1,"different")).getCode());
    }
    @ParameterizedTest @EnumSource(EnrollmentCoordinator.Strategy.class)
    void fullFastPathStillReplaysConcurrentIdempotencyKeys(EnrollmentCoordinator.Strategy strategy) throws Exception {
        db.update("UPDATE courses SET capacity=1 WHERE id=1");
        var service=service(strategy);
        var results=parallel(32,i -> Long.toString(service.enroll(1,1,"last-seat").enrollmentId()));
        assertEquals(1,new HashSet<>(results).size());
        assertTrue(results.get(0).matches("[0-9]+"),results.toString());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM enrollments",Integer.class));
    }
    private void synchronizeFirstExternalCalls() {
        var barrier = new CyclicBarrier(2);
        var calls = new AtomicInteger();
        doAnswer(call -> {
            if (calls.incrementAndGet() <= 2) barrier.await(5, TimeUnit.SECONDS);
            return null;
        }).when(external).verify(anyLong(), anyLong(), anyLong());
    }
    @ParameterizedTest @EnumSource(EnrollmentCoordinator.Strategy.class)
    void sameStudentDifferentCoursesCannotOverlap(EnrollmentCoordinator.Strategy strategy) throws Exception {
        synchronizeFirstExternalCalls();
        var service=service(strategy);
        var outcomes=parallel(2,i -> { service.enroll(1,i==0?1:4,"schedule-"+i); return "OK"; });
        assertEquals(1,Collections.frequency(outcomes,"OK"));
        assertEquals(1,Collections.frequency(outcomes,"SCHEDULE_CONFLICT"));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM enrollments",Integer.class));
        assertEquals(1,db.queryForObject("SELECT SUM(enrolled_count) FROM courses",Integer.class));
    }
    @ParameterizedTest @EnumSource(EnrollmentCoordinator.Strategy.class)
    void sameStudentCannotExceedCreditLimit(EnrollmentCoordinator.Strategy strategy) throws Exception {
        synchronizeFirstExternalCalls();
        db.update("UPDATE students SET max_credits=3 WHERE id=1");
        var service=service(strategy);
        var outcomes=parallel(2,i -> { service.enroll(1,1+i,"credit-"+i); return "OK"; });
        assertEquals(1,Collections.frequency(outcomes,"OK"));
        assertEquals(1,Collections.frequency(outcomes,"CREDIT_LIMIT_EXCEEDED"));
    }
    @Test void expiredOwnerCannotDeleteSuccessorLockOrCommit() {
        doAnswer(call -> {
            strings.opsForValue().set("enrollment:course:1","successor",java.time.Duration.ofSeconds(10));
            return null;
        }).when(external).verify(anyLong(),anyLong(),anyLong());
        var ex=assertThrows(EnrollmentException.class,()->service(EnrollmentCoordinator.Strategy.REDIS).enroll(1,1,"lost"));
        assertEquals("LEASE_LOST",ex.getCode());
        assertEquals("successor",strings.opsForValue().get("enrollment:course:1"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM enrollments",Integer.class));
    }
    @Test void actualLeaseExpiryDuringTransactionRollsBackEverything() throws Exception {
        gate = new RedisGate(strings,200);
        var blocker=Objects.requireNonNull(db.getDataSource()).getConnection();
        blocker.setAutoCommit(false);
        blocker.createStatement().execute("SELECT * FROM students WHERE id=1 FOR UPDATE");
        var pool=Executors.newSingleThreadExecutor();
        try {
            var result=pool.submit(()->assertThrows(EnrollmentException.class,()->service(EnrollmentCoordinator.Strategy.REDIS).enroll(1,1,"expire-tx")));
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(!Boolean.TRUE.equals(strings.hasKey("enrollment:course:1")) && System.nanoTime()<until) Thread.sleep(5);
            assertTrue(Boolean.TRUE.equals(strings.hasKey("enrollment:course:1")));
            Thread.sleep(350);
            blocker.rollback();
            assertEquals("LEASE_LOST",result.get(5,TimeUnit.SECONDS).getCode());
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM enrollments",Integer.class));
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM enrollment_requests",Integer.class));
            assertEquals(0,db.queryForObject("SELECT enrolled_count FROM courses WHERE id=1",Integer.class));
        } finally { blocker.close(); pool.shutdownNow(); }
    }
    @Test void timeoutHasNoDurableEffectsAndSameKeyCanRetry() {
        doThrow(RedisGate.failure("EXTERNAL_RESPONSE_TIMEOUT")).when(external).verify(anyLong(),anyLong(),anyLong());
        var service=service(EnrollmentCoordinator.Strategy.REDIS);
        assertEquals("EXTERNAL_RESPONSE_TIMEOUT",assertThrows(EnrollmentException.class,()->service.enroll(1,1,"timeout")).getCode());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM enrollment_requests",Integer.class));
        reset(external);
        var created=service.enroll(1,1,"timeout");
        var replay=service.enroll(1,1,"timeout");
        assertEquals(created.enrollmentId(),replay.enrollmentId());
        assertTrue(replay.replayed());
    }
    @Test void lockWaitIsBounded() {
        strings.opsForValue().set("enrollment:course:1","other",java.time.Duration.ofSeconds(5));
        assertEquals("REDIS_LOCK_TIMEOUT",assertThrows(EnrollmentException.class,()->gate.acquire(1,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(30))).getCode());
    }

    private com.jiyun.classenrollment.exchange.ExchangeService exchange(com.jiyun.classenrollment.exchange.PartnerClient partner) {
        return new com.jiyun.classenrollment.exchange.ExchangeService(db,gate,partner,service(EnrollmentCoordinator.Strategy.REDIS));
    }
    private com.jiyun.classenrollment.exchange.PartnerClient partner() {
        var p=mock(com.jiyun.classenrollment.exchange.PartnerClient.class);
        when(p.status(anyString(),anyLong(),anyLong(),anyLong())).thenReturn("HELD");
        when(p.confirm(anyString(),anyLong(),anyLong(),anyLong())).thenReturn("CONFIRMED");
        when(p.cancel(anyString(),anyLong(),anyLong(),anyLong())).thenReturn("CANCELLED");
        return p;
    }
    @Test void exchangeReplayAndPayloadBinding() throws Exception {
        var p=partner(); var svc=exchange(p);
        var ids=parallel(32,i->svc.submit(1,1,"exchange-same").requestId());
        assertEquals(1,new HashSet<>(ids).size());
        assertEquals("COMPLETED",svc.get(ids.get(0)).phase());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM enrollments",Integer.class));
        assertEquals("IDEMPOTENCY_KEY_REUSED",assertThrows(EnrollmentException.class,()->svc.submit(1,2,"exchange-same")).getCode());
        verify(p,times(1)).confirm(anyString(),anyLong(),anyLong(),anyLong());
    }
    @Test void exchangeUnknownReserveIsQueriedAfterRestart() {
        var p=partner(); var svc=exchange(p);
        when(p.status(anyString(),anyLong(),anyLong(),anyLong())).thenReturn("ABSENT","HELD","HELD");
        when(p.reserve(anyString(),anyLong(),anyLong(),anyLong())).thenThrow(RedisGate.failure("PARTNER_RESPONSE_TIMEOUT"));
        var pending=svc.submit(1,1,"unknown-reserve");
        assertEquals("RESERVING",pending.phase());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM enrollments",Integer.class));
        var restarted=exchange(p);
        restarted.process(pending.requestId());
        assertEquals("COMPLETED",restarted.get(pending.requestId()).phase());
        verify(p,times(1)).reserve(anyString(),anyLong(),anyLong(),anyLong());
    }
    @Test void exchangeConfirmTimeoutKeepsLocalSeatAndRecovers() {
        var p=partner(); var svc=exchange(p);
        when(p.confirm(anyString(),anyLong(),anyLong(),anyLong())).thenThrow(RedisGate.failure("PARTNER_RESPONSE_TIMEOUT"));
        var pending=svc.submit(1,1,"unknown-confirm");
        assertEquals("CONFIRMING",pending.phase());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM enrollments",Integer.class));
        when(p.status(anyString(),anyLong(),anyLong(),anyLong())).thenReturn("CONFIRMED");
        exchange(p).process(pending.requestId());
        assertEquals("COMPLETED",svc.get(pending.requestId()).phase());
        assertEquals(1,db.queryForObject("SELECT enrolled_count FROM courses WHERE id=1",Integer.class));
        verify(p,never()).cancel(anyString(),anyLong(),anyLong(),anyLong());
    }
    @Test void exchangeRejectedLocallyCancelsAndRetriesUnknownCancel() {
        var p=partner(); var svc=exchange(p);
        db.update("UPDATE students SET max_credits=1 WHERE id=1");
        when(p.cancel(anyString(),anyLong(),anyLong(),anyLong())).thenThrow(RedisGate.failure("PARTNER_RESPONSE_TIMEOUT")).thenReturn("CANCELLED");
        var pending=svc.submit(1,1,"cancel");
        assertEquals("CANCELLING",pending.phase());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM enrollments",Integer.class));
        svc.process(pending.requestId());
        assertEquals("CANCELLED",svc.get(pending.requestId()).phase());
        assertEquals("CREDIT_LIMIT_EXCEEDED",svc.get(pending.requestId()).lastError());
        verify(p,never()).confirm(anyString(),anyLong(),anyLong(),anyLong());
    }
    @Test void exchangeLeaseLossPreservesIntentForRecovery() {
        var p=partner(); var svc=exchange(p);
        when(p.status(anyString(),anyLong(),anyLong(),anyLong())).thenAnswer(call->{
            strings.opsForValue().set("enrollment:course:1","successor",java.time.Duration.ofSeconds(10));
            return "HELD";
        });
        var pending=svc.submit(1,1,"exchange-lease");
        assertEquals("RESERVING",pending.phase());
        assertEquals("LEASE_LOST",pending.lastError());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM enrollments",Integer.class));
        assertEquals("successor",strings.opsForValue().get("enrollment:course:1"));
        strings.delete("enrollment:course:1");
        doReturn("HELD").when(p).status(anyString(),anyLong(),anyLong(),anyLong());
        svc.process(pending.requestId());
        assertEquals("COMPLETED",svc.get(pending.requestId()).phase());
    }
    @Test void exchangeExpiredClaimCanBeRecoveredButActiveClaimCannot() {
        var p=partner(); var svc=exchange(p);
        String id=UUID.randomUUID().toString();
        db.update("INSERT INTO exchange_requests(id,student_id,course_id,request_key,owner,claim_until) VALUES (?,1,1,'crashed','old',DATE_ADD(NOW(6),INTERVAL 30 SECOND))",id);
        svc.process(id);
        verifyNoInteractions(p);
        db.update("UPDATE exchange_requests SET claim_until=DATE_SUB(NOW(6),INTERVAL 1 SECOND) WHERE id=?",id);
        svc.recover();
        assertEquals("COMPLETED",svc.get(id).phase());
    }
}
