package com.jiyun.classenrollment.exchange;

import com.jiyun.classenrollment.enrollment.api.EnrollmentRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/exchange-enrollments")
public class ExchangeController {
    private final ExchangeService service;
    public ExchangeController(ExchangeService service) { this.service=service; }
    @PostMapping
    public ResponseEntity<ExchangeService.View> submit(@Valid @RequestBody EnrollmentRequest request,
            @RequestHeader(value="Idempotency-Key",required=false) String key) {
        var view=service.submit(request.studentId(),request.courseId(),key);
        return ResponseEntity.status(view.phase().equals("COMPLETED")?200:view.phase().equals("CANCELLED")?409:202).body(view);
    }
    @GetMapping("/{id}") public ExchangeService.View status(@PathVariable String id) { return service.get(id); }
}
