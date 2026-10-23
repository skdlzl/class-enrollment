CREATE TABLE academic_student (
 run_id VARCHAR(36) NOT NULL, id BIGINT NOT NULL, name VARCHAR(100) NOT NULL,
 status VARCHAR(20) NOT NULL, max_credits INT NOT NULL, PRIMARY KEY(run_id,id));
CREATE TABLE subject (
 run_id VARCHAR(36) NOT NULL, id BIGINT NOT NULL, code VARCHAR(30) NOT NULL,
 name VARCHAR(100) NOT NULL, description VARCHAR(400) NOT NULL, department VARCHAR(20) NOT NULL,
 PRIMARY KEY(run_id,id), INDEX idx_subject_search(run_id,department,id));
