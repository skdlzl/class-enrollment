package com.jiyun.classenrollment.enrollment.application;

import com.jiyun.classenrollment.common.error.EnrollmentException;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.redisson.client.codec.StringCodec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClosedCourseHintsTest {
    private final RedissonClient redis=mock(RedissonClient.class);
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    @SuppressWarnings("unchecked") private final RBucket<String> bucket=mock(RBucket.class);
    private ClosedCourseHints hints(){
        when(redis.<String>getBucket("enrollment:closed-hint:v1:10",StringCodec.INSTANCE)).thenReturn(bucket);
        return new ClosedCourseHints(redis,jdbc,true,5000);
    }
    @Test void disabledDoesNotAccessRedisOrDatabase(){
        var hints=new ClosedCourseHints(redis,jdbc,false,5000);
        hints.rejectIfClosed(10L);hints.mark(10L);verifyNoInteractions(redis,jdbc);
    }
    @Test void missDoesNotReadDatabase(){
        hints().rejectIfClosed(10L);verifyNoInteractions(jdbc);
    }
    @Test void committedFullHitRejectsBeforeStudentLock(){
        var hints=hints();when(bucket.isExists()).thenReturn(true);
        when(jdbc.queryForList(anyString(),eq(Boolean.class),eq(10L))).thenReturn(List.of(true));
        var service=mock(EnrollmentService.class);
        var facade=new EnrollmentRedissonFacade(service,redis,CourseLockStrategy.PESSIMISTIC);
        ReflectionTestUtils.setField(facade,"closedCourseHints",hints);
        assertEquals("COURSE_FULL",assertThrows(EnrollmentException.class,()->facade.enroll(1L,10L)).getCode());
        verify(redis,never()).getLock(anyString());verifyNoInteractions(service);
        assertEquals(1L,hints.snapshot().get("rejected"));
    }
    @Test void stalePositiveIsClearedAndCannotRejectReopenedCourse(){
        var hints=hints();when(bucket.isExists()).thenReturn(true);
        when(jdbc.queryForList(anyString(),eq(Boolean.class),eq(10L))).thenReturn(List.of(false));
        assertDoesNotThrow(()->hints.rejectIfClosed(10L));verify(bucket).delete();
    }
    @Test void redisFailuresFallBackAndCannotOverwriteCommitResult(){
        var hints=hints();when(bucket.isExists()).thenThrow(new RedisException("offline"));
        doThrow(new RedisException("offline")).when(bucket).set("closed",Duration.ofMillis(5000));
        assertDoesNotThrow(()->hints.rejectIfClosed(10L));assertDoesNotThrow(()->hints.mark(10L));
        verifyNoInteractions(jdbc);assertEquals(2L,hints.snapshot().get("redisErrors"));
    }
    @Test void failedHintConfirmationFallsBackToGuardedWrite(){
        var hints=hints();when(bucket.isExists()).thenReturn(true);
        when(jdbc.queryForList(anyString(),eq(Boolean.class),eq(10L))).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db"));
        assertDoesNotThrow(()->hints.rejectIfClosed(10L));assertEquals(1L,hints.snapshot().get("dbErrors"));
    }
    @Test void marksOnlyAfterCommitNotWhenRegisteringOrRollingBack(){
        var hints=hints();TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            hints.markAfterCommit(10L);verify(bucket,never()).set(anyString(),any(Duration.class));
            var callback=TransactionSynchronizationManager.getSynchronizations().get(0);
            callback.afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK);
            verify(bucket,never()).set(anyString(),any(Duration.class));
            callback.afterCommit();verify(bucket).set("closed",Duration.ofMillis(5000));
        } finally {TransactionSynchronizationManager.clearSynchronization();TransactionSynchronizationManager.setActualTransactionActive(false);}
    }
}
