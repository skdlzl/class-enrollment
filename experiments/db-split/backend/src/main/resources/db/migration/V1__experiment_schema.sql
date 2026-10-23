-- All run-scoped rows are retained. No destructive reset or cross-domain FK.
CREATE TABLE experiment_run (run_id VARCHAR(36) PRIMARY KEY, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE academic_student (
 run_id VARCHAR(36) NOT NULL, id BIGINT NOT NULL, name VARCHAR(100) NOT NULL,
 status VARCHAR(20) NOT NULL, max_credits INT NOT NULL, PRIMARY KEY(run_id,id));
CREATE TABLE subject (
 run_id VARCHAR(36) NOT NULL, id BIGINT NOT NULL, code VARCHAR(30) NOT NULL,
 name VARCHAR(100) NOT NULL, description VARCHAR(400) NOT NULL, department VARCHAR(20) NOT NULL,
 PRIMARY KEY(run_id,id), INDEX idx_subject_search(run_id,department,id));
CREATE TABLE student_term (
 run_id VARCHAR(36) NOT NULL, student_id BIGINT NOT NULL, term_id INT NOT NULL,
 status VARCHAR(20) NOT NULL, max_credits INT NOT NULL, enrolled_credits INT NOT NULL DEFAULT 0,
 snapshot_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(run_id,student_id,term_id), CHECK(enrolled_credits>=0 AND enrolled_credits<=max_credits));
CREATE TABLE offered_course (
 run_id VARCHAR(36) NOT NULL, id BIGINT NOT NULL, term_id INT NOT NULL, subject_id BIGINT NOT NULL,
 name VARCHAR(100) NOT NULL, department VARCHAR(20) NOT NULL, grade INT NOT NULL,
 credits INT NOT NULL, capacity INT NOT NULL, enrolled_count INT NOT NULL DEFAULT 0,
 PRIMARY KEY(run_id,id), UNIQUE(run_id,id,term_id),
 INDEX idx_offered_search(run_id,term_id,department,grade,id),
 CHECK(credits>0), CHECK(capacity>0), CHECK(enrolled_count>=0 AND enrolled_count<=capacity));
CREATE TABLE offering_schedule (
 run_id VARCHAR(36) NOT NULL, course_id BIGINT NOT NULL, day_no INT NOT NULL,
 start_minute INT NOT NULL, end_minute INT NOT NULL,
 PRIMARY KEY(run_id,course_id,day_no,start_minute),
 FOREIGN KEY(run_id,course_id) REFERENCES offered_course(run_id,id), CHECK(start_minute<end_minute));
CREATE TABLE enrollment (
 request_id VARCHAR(36) PRIMARY KEY, run_id VARCHAR(36) NOT NULL, student_id BIGINT NOT NULL,
 term_id INT NOT NULL, course_id BIGINT NOT NULL, status VARCHAR(12) NOT NULL DEFAULT 'CONFIRMED',
 UNIQUE(run_id,student_id,term_id,course_id),
 FOREIGN KEY(run_id,student_id,term_id) REFERENCES student_term(run_id,student_id,term_id),
 FOREIGN KEY(run_id,course_id,term_id) REFERENCES offered_course(run_id,id,term_id),
 INDEX idx_enrollment_course(run_id,course_id,status), CHECK(status IN ('CONFIRMED','CANCELLED')));
