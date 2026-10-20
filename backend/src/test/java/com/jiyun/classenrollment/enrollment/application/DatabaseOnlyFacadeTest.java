package com.jiyun.classenrollment.enrollment.application;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.dao.PessimisticLockingFailureException;
import com.jiyun.classenrollment.common.error.EnrollmentException;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class DatabaseOnlyFacadeTest {
    @Test void noRedisCallsOnSuccessOrDatabaseLockFailure() {
        var service=mock(EnrollmentService.class);var redis=mock(RedissonClient.class);
        var facade=new EnrollmentRedissonFacade(service,redis,CourseLockStrategy.DB_ONLY);
        when(service.enrollWithDatabaseLocks(1L,2L)).thenReturn(3L)
            .thenThrow(new PessimisticLockingFailureException("timeout"));
        assertEquals(3L,facade.enroll(1L,2L));
        assertEquals("DB_LOCK_FAILURE",assertThrows(EnrollmentException.class,()->facade.enroll(1L,2L)).getCode());
        verifyNoInteractions(redis);
        verify(service,never()).validateForEnrollment(anyLong(),anyLong());
    }
}
