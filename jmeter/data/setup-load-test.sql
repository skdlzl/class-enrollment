-- JMeter 부하 테스트 전용 데이터 초기화 스크립트
-- Flyway 마이그레이션이 아니라 필요할 때 수동 실행합니다.

START TRANSACTION;

DELETE FROM enrollments
WHERE student_id BETWEEN 10000 AND 19999
   OR course_id BETWEEN 1 AND 50;

DELETE FROM students
WHERE id BETWEEN 10000 AND 19999;

DELETE FROM course_schedules
WHERE course_id BETWEEN 1 AND 50;

DELETE FROM courses
WHERE id BETWEEN 1 AND 50;

INSERT INTO courses (
    id,
    course_code,
    name,
    professor,
    department,
    academic_year,
    semester,
    credits,
    capacity,
    enrolled_count
)
VALUES
(1, 'LOAD001', 'Load Test Course 1', 'Professor 1', 'Load Test', 2026, 2, 3, 100, 0),
(2, 'LOAD002', 'Load Test Course 2', 'Professor 2', 'Load Test', 2026, 2, 3, 50, 0),
(3, 'LOAD003', 'Load Test Course 3', 'Professor 3', 'Load Test', 2026, 2, 3, 100, 0),
(4, 'LOAD004', 'Load Test Course 4', 'Professor 4', 'Load Test', 2026, 2, 3, 50, 0),
(5, 'LOAD005', 'Load Test Course 5', 'Professor 5', 'Load Test', 2026, 2, 3, 100, 0),
(6, 'LOAD006', 'Load Test Course 6', 'Professor 6', 'Load Test', 2026, 2, 3, 50, 0),
(7, 'LOAD007', 'Load Test Course 7', 'Professor 7', 'Load Test', 2026, 2, 3, 100, 0),
(8, 'LOAD008', 'Load Test Course 8', 'Professor 8', 'Load Test', 2026, 2, 3, 50, 0),
(9, 'LOAD009', 'Load Test Course 9', 'Professor 9', 'Load Test', 2026, 2, 3, 100, 0),
(10, 'LOAD010', 'Load Test Course 10', 'Professor 10', 'Load Test', 2026, 2, 3, 50, 0),
(11, 'LOAD011', 'Load Test Course 11', 'Professor 11', 'Load Test', 2026, 2, 3, 100, 0),
(12, 'LOAD012', 'Load Test Course 12', 'Professor 12', 'Load Test', 2026, 2, 3, 50, 0),
(13, 'LOAD013', 'Load Test Course 13', 'Professor 13', 'Load Test', 2026, 2, 3, 100, 0),
(14, 'LOAD014', 'Load Test Course 14', 'Professor 14', 'Load Test', 2026, 2, 3, 50, 0),
(15, 'LOAD015', 'Load Test Course 15', 'Professor 15', 'Load Test', 2026, 2, 3, 100, 0),
(16, 'LOAD016', 'Load Test Course 16', 'Professor 16', 'Load Test', 2026, 2, 3, 50, 0),
(17, 'LOAD017', 'Load Test Course 17', 'Professor 17', 'Load Test', 2026, 2, 3, 100, 0),
(18, 'LOAD018', 'Load Test Course 18', 'Professor 18', 'Load Test', 2026, 2, 3, 50, 0),
(19, 'LOAD019', 'Load Test Course 19', 'Professor 19', 'Load Test', 2026, 2, 3, 100, 0),
(20, 'LOAD020', 'Load Test Course 20', 'Professor 20', 'Load Test', 2026, 2, 3, 50, 0),
(21, 'LOAD021', 'Load Test Course 21', 'Professor 21', 'Load Test', 2026, 2, 3, 100, 0),
(22, 'LOAD022', 'Load Test Course 22', 'Professor 22', 'Load Test', 2026, 2, 3, 50, 0),
(23, 'LOAD023', 'Load Test Course 23', 'Professor 23', 'Load Test', 2026, 2, 3, 100, 0),
(24, 'LOAD024', 'Load Test Course 24', 'Professor 24', 'Load Test', 2026, 2, 3, 50, 0),
(25, 'LOAD025', 'Load Test Course 25', 'Professor 25', 'Load Test', 2026, 2, 3, 100, 0),
(26, 'LOAD026', 'Load Test Course 26', 'Professor 26', 'Load Test', 2026, 2, 3, 50, 0),
(27, 'LOAD027', 'Load Test Course 27', 'Professor 27', 'Load Test', 2026, 2, 3, 100, 0),
(28, 'LOAD028', 'Load Test Course 28', 'Professor 28', 'Load Test', 2026, 2, 3, 50, 0),
(29, 'LOAD029', 'Load Test Course 29', 'Professor 29', 'Load Test', 2026, 2, 3, 100, 0),
(30, 'LOAD030', 'Load Test Course 30', 'Professor 30', 'Load Test', 2026, 2, 3, 50, 0),
(31, 'LOAD031', 'Load Test Course 31', 'Professor 31', 'Load Test', 2026, 2, 3, 100, 0),
(32, 'LOAD032', 'Load Test Course 32', 'Professor 32', 'Load Test', 2026, 2, 3, 50, 0),
(33, 'LOAD033', 'Load Test Course 33', 'Professor 33', 'Load Test', 2026, 2, 3, 100, 0),
(34, 'LOAD034', 'Load Test Course 34', 'Professor 34', 'Load Test', 2026, 2, 3, 50, 0),
(35, 'LOAD035', 'Load Test Course 35', 'Professor 35', 'Load Test', 2026, 2, 3, 100, 0),
(36, 'LOAD036', 'Load Test Course 36', 'Professor 36', 'Load Test', 2026, 2, 3, 50, 0),
(37, 'LOAD037', 'Load Test Course 37', 'Professor 37', 'Load Test', 2026, 2, 3, 100, 0),
(38, 'LOAD038', 'Load Test Course 38', 'Professor 38', 'Load Test', 2026, 2, 3, 50, 0),
(39, 'LOAD039', 'Load Test Course 39', 'Professor 39', 'Load Test', 2026, 2, 3, 100, 0),
(40, 'LOAD040', 'Load Test Course 40', 'Professor 40', 'Load Test', 2026, 2, 3, 50, 0),
(41, 'LOAD041', 'Load Test Course 41', 'Professor 41', 'Load Test', 2026, 2, 3, 100, 0),
(42, 'LOAD042', 'Load Test Course 42', 'Professor 42', 'Load Test', 2026, 2, 3, 50, 0),
(43, 'LOAD043', 'Load Test Course 43', 'Professor 43', 'Load Test', 2026, 2, 3, 100, 0),
(44, 'LOAD044', 'Load Test Course 44', 'Professor 44', 'Load Test', 2026, 2, 3, 50, 0),
(45, 'LOAD045', 'Load Test Course 45', 'Professor 45', 'Load Test', 2026, 2, 3, 100, 0),
(46, 'LOAD046', 'Load Test Course 46', 'Professor 46', 'Load Test', 2026, 2, 3, 50, 0),
(47, 'LOAD047', 'Load Test Course 47', 'Professor 47', 'Load Test', 2026, 2, 3, 100, 0),
(48, 'LOAD048', 'Load Test Course 48', 'Professor 48', 'Load Test', 2026, 2, 3, 50, 0),
(49, 'LOAD049', 'Load Test Course 49', 'Professor 49', 'Load Test', 2026, 2, 3, 100, 0),
(50, 'LOAD050', 'Load Test Course 50', 'Professor 50', 'Load Test', 2026, 2, 3, 50, 0);

INSERT INTO students (
    id,
    student_number,
    name,
    status,
    max_credits
)
SELECT
    10000 + numbers.number_value,
    CONCAT('LOAD', LPAD(numbers.number_value, 5, '0')),
    CONCAT('Load Student ', numbers.number_value),
    'ACTIVE',
    18
FROM (
    SELECT
        ones.digit
        + tens.digit * 10
        + hundreds.digit * 100
        + thousands.digit * 1000 AS number_value
    FROM
        (SELECT 0 digit UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
         UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) ones
    CROSS JOIN
        (SELECT 0 digit UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
         UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) tens
    CROSS JOIN
        (SELECT 0 digit UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
         UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) hundreds
    CROSS JOIN
        (SELECT 0 digit UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
         UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) thousands
) numbers;

COMMIT;

-- 준비 결과 확인
SELECT COUNT(*) AS student_count
FROM students
WHERE id BETWEEN 10000 AND 19999;

SELECT
    COUNT(*) AS course_count,
    SUM(capacity) AS total_capacity,
    SUM(enrolled_count) AS total_enrolled_count
FROM courses
WHERE id BETWEEN 1 AND 50;

SELECT id, course_code, capacity, enrolled_count
FROM courses
WHERE id IN (1, 2, 49, 50)
ORDER BY id;
