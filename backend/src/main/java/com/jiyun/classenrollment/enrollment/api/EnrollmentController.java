package com.jiyun.classenrollment.enrollment.api;

import com.jiyun.classenrollment.enrollment.application.EnrollmentRedissonFacade;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/enrollments")
public class EnrollmentController {

    private final EnrollmentRedissonFacade enrollmentRedissonFacade;
    @org.springframework.beans.factory.annotation.Value("${enrollment.queue.enabled:false}")
    private boolean queueEnabled;

    public EnrollmentController(EnrollmentRedissonFacade enrollmentRedissonFacade) {
        this.enrollmentRedissonFacade = enrollmentRedissonFacade;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EnrollmentResponse enroll(@Valid @RequestBody EnrollmentRequest request) {
        if(queueEnabled) throw new org.springframework.web.server.ResponseStatusException(
            HttpStatus.CONFLICT, "대기 접수 API /api/enrollment-requests를 사용해 주세요.");
        Long enrollmentId = enrollmentRedissonFacade.enroll(request.studentId(), request.courseId());
        return new EnrollmentResponse(enrollmentId, "수강신청이 완료되었습니다.");
    }
}
