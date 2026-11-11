package com.jiyun.dbsplit;

import org.junit.jupiter.api.*;
import org.redisson.api.*;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClosedCoursePrecheckTest {
    final EnrollmentService.Request r=new EnrollmentService.Request("req","run",1,202602,1);
    RedissonClient redis; RBucket<Object> bucket; JdbcTemplate jdbc; ClosedCoursePrecheck guard;
    @BeforeEach @SuppressWarnings("unchecked") void setup(){
        redis=mock(RedissonClient.class); bucket=mock(RBucket.class); jdbc=mock(JdbcTemplate.class);
        ObjectProvider<RedissonClient> provider=mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redis);
        guard=new ClosedCoursePrecheck(true,provider,jdbc,new Metrics(true));
        when(redis.getBucket(guard.key(r),StringCodec.INSTANCE)).thenReturn(bucket);
    }
    void reject(boolean value){
        when(jdbc.queryForObject(
            "SELECT NOT EXISTS(SELECT 1 FROM enrollment WHERE request_id=?) AND EXISTS(SELECT 1 FROM offered_course WHERE run_id=? AND term_id=? AND id=? AND enrolled_count>=capacity)",
            Boolean.class,r.requestId(),r.runId(),r.termId(),r.courseId())).thenReturn(value);
    }
    void full(boolean value){
        when(jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM offered_course WHERE run_id=? AND term_id=? AND id=? AND enrolled_count>=capacity)",
            Boolean.class,r.runId(),r.termId(),r.courseId())).thenReturn(value);
    }
    @Test void cacheMissDoesNotQueryDb(){guard.check(r);verifyNoInteractions(jdbc);}
    @Test void redisFailureFallsBackToNormalEnrollment(){
        when(bucket.isExists()).thenThrow(new RuntimeException("redis down"));
        assertDoesNotThrow(()->guard.check(r));verifyNoInteractions(jdbc);
    }
    @Test void staleHintAfterCancellationDoesNotReject(){
        when(bucket.isExists()).thenReturn(true);reject(false);
        assertDoesNotThrow(()->guard.check(r));verify(bucket).delete();
    }
    @Test void retryBypassesFullHintForNormalIdempotencyCheck(){
        when(bucket.isExists()).thenReturn(true);reject(false);
        assertDoesNotThrow(()->guard.check(r));verify(bucket).delete();
    }
    @Test void authoritativeFullRejects(){
        when(bucket.isExists()).thenReturn(true);reject(true);
        assertEquals("COURSE_FULL",assertThrows(EnrollmentService.Rejected.class,()->guard.check(r)).code);
    }
    @Test void hintPublicationFailureDoesNotReplaceCommittedResult(){
        full(true);doThrow(new RuntimeException("redis down")).when(bucket).set(eq("1"),any(java.time.Duration.class));
        assertDoesNotThrow(()->guard.refresh(r));
    }
}
