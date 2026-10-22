package com.jiyun.classenrollment.enrollment.api;

import com.jiyun.classenrollment.common.error.EnrollmentException;
import com.jiyun.classenrollment.common.error.GlobalExceptionHandler;
import com.jiyun.classenrollment.enrollment.application.EnrollmentService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class EnrollmentQueryControllerTest {
    @Test void committedEnrollmentCanBeLookedUpAfterLostResponse() throws Exception {
        var service = mock(EnrollmentService.class);
        when(service.findConfirmedEnrollment(1L, 2L)).thenReturn(99L);
        var mvc = MockMvcBuilders.standaloneSetup(new EnrollmentQueryController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/api/enrollments/confirmation").param("studentId", "1").param("courseId", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enrollmentId").value(99));
        verify(service, never()).completeEnrollmentWithPessimisticLock(anyLong(), anyLong());
    }

    @Test void absentEnrollmentReturns404RatherThanFalseConfirmation() throws Exception {
        var service = mock(EnrollmentService.class);
        when(service.findConfirmedEnrollment(1L, 2L)).thenThrow(new EnrollmentException(
                "ENROLLMENT_NOT_FOUND", "확정 내역 없음", HttpStatus.NOT_FOUND));
        var mvc = MockMvcBuilders.standaloneSetup(new EnrollmentQueryController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/api/enrollments/confirmation").param("studentId", "1").param("courseId", "2"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ENROLLMENT_NOT_FOUND"));
    }
}
