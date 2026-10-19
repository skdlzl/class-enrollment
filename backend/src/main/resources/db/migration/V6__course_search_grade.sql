ALTER TABLE courses ADD COLUMN target_grade INT NOT NULL DEFAULT 1;
CREATE INDEX idx_courses_catalog_grade ON courses(academic_year, semester, department, target_grade, id);
