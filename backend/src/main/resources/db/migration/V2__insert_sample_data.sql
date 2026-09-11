INSERT INTO students (id, student_number, name, status, max_credits)
VALUES (1, '20260001', '김지윤', 'ACTIVE', 18),
       (2, '20260002', '이서준', 'ACTIVE', 18),
       (3, '20260003', '박하린', 'LEAVE', 18);

INSERT INTO courses (id, course_code, name, professor, department, academic_year, semester, credits, capacity, enrolled_count)
VALUES (1, 'CSE301', '분산시스템', '김교수', '컴퓨터공학과', 2026, 2, 3, 30, 0),
       (2, 'CSE302', '데이터베이스', '이교수', '컴퓨터공학과', 2026, 2, 3, 40, 0),
       (3, 'CSE303', '운영체제', '박교수', '컴퓨터공학과', 2026, 2, 3, 35, 0),
       (4, 'CSE304', '컴퓨터네트워크', '최교수', '컴퓨터공학과', 2026, 2, 3, 30, 0);

INSERT INTO course_schedules (course_id, day_of_week, start_time, end_time)
VALUES (1, 'MONDAY', '09:00:00', '10:30:00'),
       (1, 'WEDNESDAY', '09:00:00', '10:30:00'),
       (2, 'TUESDAY', '10:30:00', '12:00:00'),
       (2, 'THURSDAY', '10:30:00', '12:00:00'),
       (3, 'MONDAY', '13:00:00', '14:30:00'),
       (3, 'WEDNESDAY', '13:00:00', '14:30:00'),
       (4, 'TUESDAY', '15:00:00', '16:30:00'),
       (4, 'THURSDAY', '15:00:00', '16:30:00');

