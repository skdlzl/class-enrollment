package com.jiyun.classenrollment.exchange;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ExchangeController.class)
class ExchangeControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean ExchangeService service;
    @Test void queuedRequestExposesRetryAdviceWithoutClaimingCompletion() throws Exception {
        when(service.submit(1,1,"key")).thenReturn(new ExchangeService.View("request","RESERVING",null,null));
        mvc.perform(post("/api/exchange-enrollments").contentType("application/json").header("Idempotency-Key","key")
            .content("{\"studentId\":1,\"courseId\":1}"))
            .andExpect(status().isAccepted()).andExpect(header().string("Retry-After","1"))
            .andExpect(jsonPath("$.phase").value("RESERVING"));
    }
    @Test void failureBeforeClaimHasRetryableServiceUnavailableResponse() throws Exception {
        when(service.submit(1,1,"key")).thenThrow(new org.springframework.jdbc.CannotGetJdbcConnectionException("pool exhausted"));
        mvc.perform(post("/api/exchange-enrollments").contentType("application/json").header("Idempotency-Key","key")
            .content("{\"studentId\":1,\"courseId\":1}"))
            .andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After","1"))
            .andExpect(jsonPath("$.code").value("DB_CONNECTION_UNAVAILABLE"));
    }
}
