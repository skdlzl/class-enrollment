CREATE TABLE notification_outbox (
 id VARCHAR(36) PRIMARY KEY, request_id VARCHAR(36) NOT NULL, run_id VARCHAR(36) NOT NULL,
 event_type VARCHAR(20) NOT NULL, payload JSON NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'PENDING', owner VARCHAR(36), attempts INT NOT NULL DEFAULT 0,
 lease_until TIMESTAMP(6) NULL, next_attempt TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), sent_at TIMESTAMP(6) NULL,
 last_error VARCHAR(100), UNIQUE(request_id,event_type),
 INDEX idx_notification_pending(status,next_attempt), INDEX idx_notification_lease(status,lease_until),
 CHECK(status IN ('PENDING','PROCESSING','SENT','REVIEW'))
);
