package com.jiyun.classenrollment.enrollment.api;

import com.jiyun.classenrollment.enrollment.v2.EnrollmentCoordinator;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/enrollments")
public class EnrollmentController {
    private final EnrollmentCoordinator service;
    public EnrollmentController(EnrollmentCoordinator service) { this.service = service; }
    @PostMapping
    public ResponseEntity<EnrollmentCoordinator.Result> enroll(@Valid @RequestBody EnrollmentRequest request,
            @RequestHeader(value="Idempotency-Key", required=false) String key) {
        var result = service.enroll(request.studentId(), request.courseId(), key);
        return ResponseEntity.status(result.replayed() ? 200 : 201).body(result);
    }
}
