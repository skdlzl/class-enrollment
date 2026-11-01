package com.jiyun.classenrollment.common.error;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(EnrollmentException.class)
    public ResponseEntity<ErrorResponse> handleEnrollmentException(EnrollmentException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(new ErrorResponse(exception.getCode(), exception.getMessage()));
    }
    @ExceptionHandler({org.springframework.jdbc.CannotGetJdbcConnectionException.class,
        org.springframework.transaction.CannotCreateTransactionException.class})
    public ResponseEntity<ErrorResponse> handleConnectionFailure(RuntimeException exception) {
        return ResponseEntity.status(503).header("Retry-After","1")
            .body(new ErrorResponse("DB_CONNECTION_UNAVAILABLE","DB 연결을 확보하지 못했습니다. 같은 멱등 키로 재시도해 주세요."));
    }
}
