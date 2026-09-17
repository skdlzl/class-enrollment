CREATE TABLE students (
    id BIGINT NOT NULL AUTO_INCREMENT,
    student_number VARCHAR(20) NOT NULL,
    name VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL,
    max_credits INT NOT NULL DEFAULT 18,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_students_student_number UNIQUE (student_number),
    CONSTRAINT chk_students_max_credits CHECK (max_credits > 0)
);

CREATE TABLE courses (
    id BIGINT NOT NULL AUTO_INCREMENT,
    course_code VARCHAR(20) NOT NULL,
    name VARCHAR(100) NOT NULL,
    professor VARCHAR(50) NOT NULL,
    department VARCHAR(50) NOT NULL,
    academic_year INT NOT NULL,
    semester INT NOT NULL,
    credits INT NOT NULL,
    capacity INT NOT NULL,
    enrolled_count INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_courses_term_code UNIQUE (academic_year, semester, course_code),
    CONSTRAINT chk_courses_semester CHECK (semester IN (1, 2)),
    CONSTRAINT chk_courses_credits CHECK (credits BETWEEN 1 AND 6),
    CONSTRAINT chk_courses_capacity CHECK (capacity > 0),
    CONSTRAINT chk_courses_enrolled_count CHECK (enrolled_count BETWEEN 0 AND capacity)
);

CREATE TABLE course_schedules (
    id BIGINT NOT NULL AUTO_INCREMENT,
    course_id BIGINT NOT NULL,
    day_of_week VARCHAR(10) NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_course_schedules_course FOREIGN KEY (course_id) REFERENCES courses (id),
    CONSTRAINT chk_course_schedules_time CHECK (start_time < end_time),
    INDEX idx_course_schedules_course_id (course_id),
    INDEX idx_course_schedules_time (day_of_week, start_time, end_time)
);

CREATE TABLE enrollments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    student_id BIGINT NOT NULL,
    course_id BIGINT NOT NULL,
    enrolled_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_enrollments_student_course UNIQUE (student_id, course_id),
    CONSTRAINT fk_enrollments_student FOREIGN KEY (student_id) REFERENCES students (id),
    CONSTRAINT fk_enrollments_course FOREIGN KEY (course_id) REFERENCES courses (id),
    INDEX idx_enrollments_student_id (student_id),
    INDEX idx_enrollments_course_id (course_id)
);

