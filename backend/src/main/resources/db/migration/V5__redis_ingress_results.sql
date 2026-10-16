-- Only workers write this ledger. Invalid IDs can be recorded as REJECTED, so no foreign keys.
CREATE TABLE enrollment_ingress_results (
 request_id VARCHAR(36) PRIMARY KEY,
 student_id BIGINT NOT NULL,
 course_id BIGINT NOT NULL,
 status VARCHAR(20) NOT NULL,
 enrollment_id BIGINT NULL,
 message VARCHAR(255) NOT NULL DEFAULT '',
 created_at DATETIME(6) NOT NULL,
 INDEX idx_ingress_student(student_id)
);
