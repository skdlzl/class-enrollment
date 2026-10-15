-- Run AFTER setup-load-test.sql. Dedicated read fixture IDs; safe to re-run.
START TRANSACTION;
INSERT INTO students(id,student_number,name,status,max_credits)
SELECT 200000+(id-10000), CONCAT('READ',id-10000), CONCAT('Catalog Student ',id-10000),'ACTIVE',18
FROM students WHERE id BETWEEN 10000 AND 19999
ON DUPLICATE KEY UPDATE name=VALUES(name);

INSERT INTO courses(id,course_code,name,professor,department,academic_year,semester,credits,capacity,enrolled_count)
SELECT 100000+n, CONCAT('READ',LPAD(n,5,'0')),
       CONCAT(ELT(1+MOD(n,5),'Database Systems','Operating Systems','Algorithms','Economics','Statistics'),' ',n),
       CONCAT('Professor ',MOD(n,200)),
       ELT(1+MOD(n,5),'CS','BUSINESS','ECONOMICS','MATH','ENGINEERING'),2026,2,3,100,0
FROM (
 SELECT id-10000 AS n FROM students WHERE id BETWEEN 10000 AND 19999
 UNION ALL
 SELECT id FROM students WHERE id BETWEEN 10000 AND 19999
) numbers
ON DUPLICATE KEY UPDATE name=VALUES(name);

INSERT INTO course_schedules(course_id,day_of_week,start_time,end_time)
SELECT c.id,'MON','09:00:00','10:00:00' FROM courses c
WHERE c.id BETWEEN 100000 AND 119999 AND c.course_code LIKE 'READ%'
AND NOT EXISTS(SELECT 1 FROM course_schedules s WHERE s.course_id=c.id);

INSERT INTO enrollments(student_id,course_id)
SELECT 200000+MOD(c.id-100000,10000),c.id FROM courses c
WHERE c.id BETWEEN 100000 AND 119999 AND c.course_code LIKE 'READ%'
AND NOT EXISTS(SELECT 1 FROM enrollments e WHERE e.student_id=200000+MOD(c.id-100000,10000) AND e.course_id=c.id);

UPDATE courses c SET enrolled_count=(SELECT COUNT(*) FROM enrollments e WHERE e.course_id=c.id)
WHERE c.id BETWEEN 100000 AND 119999 AND c.course_code LIKE 'READ%';
COMMIT;
SELECT COUNT(*) AS catalog_courses FROM courses WHERE id BETWEEN 100000 AND 119999;
SELECT COUNT(*) AS catalog_students FROM students WHERE id BETWEEN 200000 AND 209999;
SELECT COUNT(*) AS catalog_enrollments FROM enrollments WHERE course_id BETWEEN 100000 AND 119999;
