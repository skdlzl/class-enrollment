package com.jiyun.classenrollment.enrollment.api;

import com.jiyun.classenrollment.enrollment.application.EnrollmentAtomicReservationFacade;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/enrollments")
public class EnrollmentController {

    private final EnrollmentAtomicReservationFacade enrollmentFacade;

    public EnrollmentController(EnrollmentAtomicReservationFacade enrollmentFacade) {
        this.enrollmentFacade = enrollmentFacade;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EnrollmentResponse enroll(@Valid @RequestBody EnrollmentRequest request) {
        Long enrollmentId = enrollmentFacade.enroll(request.studentId(), request.courseId());
        return new EnrollmentResponse(enrollmentId, "수강신청이 완료되었습니다.");
    }
}
