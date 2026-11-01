ALTER TABLE students ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE courses ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
CREATE TABLE enrollment_requests (
    student_id BIGINT NOT NULL,
    request_key VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    course_id BIGINT NOT NULL,
    enrollment_id BIGINT NULL,
    PRIMARY KEY (student_id, request_key)
);
