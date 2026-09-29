package com.jiyun.classenrollment.enrollment.application;

import com.jiyun.classenrollment.common.error.EnrollmentException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EnrollmentAtomicReservationFacadeTest {

    @Mock
    private EnrollmentService enrollmentService;

    @Mock
    private CourseSeatReservation courseSeatReservation;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock studentLock;

    private EnrollmentAtomicReservationFacade facade;

    @BeforeEach
    void setUp() {
        facade = new EnrollmentAtomicReservationFacade(
                enrollmentService,
                courseSeatReservation,
                redissonClient
        );

        when(redissonClient.getLock("lock:student:1"))
                .thenReturn(studentLock);
    }

    @Test
    void 좌석예약과_학생락_획득후_수강신청에_성공한다() throws InterruptedException {
        when(courseSeatReservation.reserve(10L)).thenReturn("permit-1");
        when(studentLock.tryLock(10L, TimeUnit.SECONDS)).thenReturn(true);
        when(studentLock.isHeldByCurrentThread()).thenReturn(true);
        when(enrollmentService.enrollWithReservedSeat(1L, 10L)).thenReturn(100L);

        Long enrollmentId = facade.enroll(1L, 10L);

        assertEquals(100L, enrollmentId);
        verify(studentLock).unlock();
        verify(courseSeatReservation, never()).release(10L, "permit-1");
    }

    @Test
    void 학생락을_얻지_못하면_예약한_좌석을_반환한다() throws InterruptedException {
        when(courseSeatReservation.reserve(10L)).thenReturn("permit-1");
        when(studentLock.tryLock(10L, TimeUnit.SECONDS)).thenReturn(false);

        EnrollmentException exception = assertThrows(
                EnrollmentException.class,
                () -> facade.enroll(1L, 10L)
        );

        assertEquals("LOCK_ACQUISITION_TIMEOUT", exception.getCode());
        verify(enrollmentService, never()).enrollWithReservedSeat(1L, 10L);
        verify(courseSeatReservation).release(10L, "permit-1");
    }

    @Test
    void DB신청에_실패하면_예약한_좌석을_반환한다() throws InterruptedException {
        when(courseSeatReservation.reserve(10L)).thenReturn("permit-1");
        when(studentLock.tryLock(10L, TimeUnit.SECONDS)).thenReturn(true);
        when(studentLock.isHeldByCurrentThread()).thenReturn(true);
        when(enrollmentService.enrollWithReservedSeat(1L, 10L))
                .thenThrow(new EnrollmentException(
                        "DUPLICATE_ENROLLMENT",
                        "이미 신청한 과목입니다.",
                        HttpStatus.CONFLICT
                ));

        assertThrows(
                EnrollmentException.class,
                () -> facade.enroll(1L, 10L)
        );

        verify(courseSeatReservation).release(10L, "permit-1");
        verify(studentLock).unlock();
    }
}
