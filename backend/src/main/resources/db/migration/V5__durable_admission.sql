-- Existing requests may already have touched the partner: never assume they are safe to discard.
ALTER TABLE exchange_requests ADD COLUMN remote_attempts BIGINT NOT NULL DEFAULT 1;
ALTER TABLE exchange_requests ALTER COLUMN remote_attempts SET DEFAULT 0;
CREATE INDEX exchange_course_queue ON exchange_requests(course_id,phase,remote_attempts DESC,created_at,id);
