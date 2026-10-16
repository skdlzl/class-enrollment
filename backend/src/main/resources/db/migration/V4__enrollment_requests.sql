CREATE TABLE enrollment_requests (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(36) NOT NULL UNIQUE,
    student_id BIGINT NOT NULL,
    course_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    attempts INT NOT NULL DEFAULT 0,
    enrollment_id BIGINT NULL,
    message VARCHAR(255) NOT NULL DEFAULT '신청이 접수되었습니다. 확정 결과를 기다려 주세요.',
    next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_request_student_course UNIQUE(student_id, course_id),
    CONSTRAINT fk_request_student FOREIGN KEY(student_id) REFERENCES students(id),
    CONSTRAINT fk_request_course FOREIGN KEY(course_id) REFERENCES courses(id),
    INDEX idx_request_recovery(status, next_attempt_at, id)
);
