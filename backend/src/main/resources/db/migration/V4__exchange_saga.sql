-- Durable intent survives client timeouts and application restarts.
CREATE TABLE exchange_requests (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    student_id BIGINT NOT NULL,
    course_id BIGINT NOT NULL,
    request_key VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    phase VARCHAR(20) NOT NULL DEFAULT 'RESERVING',
    enrollment_id BIGINT NULL,
    last_error VARCHAR(80) NULL,
    owner CHAR(36) NULL,
    claim_until DATETIME(6) NULL,
    next_attempt DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    attempts INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY exchange_idempotency (student_id,request_key),
    KEY exchange_recovery (phase,next_attempt)
);
