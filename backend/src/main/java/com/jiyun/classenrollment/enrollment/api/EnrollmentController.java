package com.jiyun.classenrollment.enrollment.api;

import com.jiyun.classenrollment.enrollment.application.EnrollmentRedissonFacade;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/enrollments")
public class EnrollmentController {

    private final EnrollmentRedissonFacade enrollmentRedissonFacade;

    public EnrollmentController(EnrollmentRedissonFacade enrollmentRedissonFacade) {
        this.enrollmentRedissonFacade = enrollmentRedissonFacade;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EnrollmentResponse enroll(@Valid @RequestBody EnrollmentRequest request) {
        Long enrollmentId = enrollmentRedissonFacade.enroll(request.studentId(), request.courseId());
        return new EnrollmentResponse(enrollmentId, "수강신청이 완료되었습니다.");
    }
}
