package com.jiyun.classenrollment.enrollment.application;

import com.jiyun.classenrollment.common.error.EnrollmentException;
import com.jiyun.classenrollment.course.domain.CourseRepository;
import com.jiyun.classenrollment.student.domain.StudentRepository;
import com.jiyun.classenrollment.enrollment.domain.EnrollmentRepository;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class GatedCapacityPrecheckTest {
    @Test void fullCourseSkipsRedisAndReleasesPermit() throws Exception {
        var service=mock(EnrollmentService.class);
        var redis=mock(RedissonClient.class);
        var facade=new EnrollmentRedissonFacade(service,redis);
        ReflectionTestUtils.setField(facade,"localGateEnabled",true);
        doThrow(new EnrollmentException("COURSE_FULL","full",HttpStatus.CONFLICT))
            .doNothing().when(service).checkCapacityBeforeStudentLock(10L);
        assertEquals("COURSE_FULL",assertThrows(EnrollmentException.class,()->facade.enroll(1L,10L)).getCode());
        verifyNoInteractions(redis);
        verify(service,never()).validateForEnrollment(anyLong(),anyLong());
        // Same-course second call must acquire the released permit and reach final locked validation.
        var student=mock(RLock.class);var course=mock(RLock.class);
        when(redis.getLock("lock:student:1")).thenReturn(student);
        when(redis.getLock("lock:course:10")).thenReturn(course);
        when(student.tryLock(10L,TimeUnit.SECONDS)).thenReturn(true);
        when(course.tryLock(10L,TimeUnit.SECONDS)).thenReturn(true);
        when(student.isHeldByCurrentThread()).thenReturn(true);
        when(course.isHeldByCurrentThread()).thenReturn(true);
        when(service.completeEnrollment(1L,10L)).thenThrow(new EnrollmentException("COURSE_FULL","filled meanwhile",HttpStatus.CONFLICT));
        assertEquals("COURSE_FULL",assertThrows(EnrollmentException.class,()->facade.enroll(1L,10L)).getCode());
        verify(service).validateForEnrollment(1L,10L);verify(service).completeEnrollment(1L,10L);
        verify(student).unlock();verify(course).unlock();
    }
    @Test void scalarCheckReadsOnlyCourseAndHonorsFlag() {
        var students=mock(StudentRepository.class);var courses=mock(CourseRepository.class);var enrollments=mock(EnrollmentRepository.class);
        var service=new EnrollmentService(students,courses,enrollments);
        when(courses.findFullById(10L)).thenReturn(Optional.of(true),Optional.of(false),Optional.empty());
        assertEquals("COURSE_FULL",assertThrows(EnrollmentException.class,()->service.checkCapacityBeforeStudentLock(10L)).getCode());
        assertDoesNotThrow(()->service.checkCapacityBeforeStudentLock(10L));
        assertEquals("COURSE_NOT_FOUND",assertThrows(EnrollmentException.class,()->service.checkCapacityBeforeStudentLock(10L)).getCode());
        verifyNoInteractions(students,enrollments);
        clearInvocations(courses);
        ReflectionTestUtils.setField(service,"capacityPrecheck",false);
        service.checkCapacityBeforeStudentLock(10L);verifyNoInteractions(courses);
    }
}
