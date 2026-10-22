package com.jiyun.classenrollment.enrollment.api;

import com.jiyun.classenrollment.enrollment.application.EnrollmentService;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Same student-id convention as the existing prototype API. Production requires authentication. */
@RestController
public class EnrollmentQueryController {
    private final EnrollmentService service;

    public EnrollmentQueryController(EnrollmentService service) {
        this.service = service;
    }

    // 404 is a point-in-time observation, not proof that an in-flight request failed.
    @GetMapping("/api/enrollments/confirmation")
    public EnrollmentResponse confirmation(@RequestParam @Positive Long studentId,
                                           @RequestParam @Positive Long courseId) {
        return new EnrollmentResponse(service.findConfirmedEnrollment(studentId, courseId),
                "수강신청이 확정되었습니다.");
    }
}
