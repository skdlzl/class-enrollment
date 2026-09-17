import type { Course, EnrollmentMap, Student } from "../types";

export const initialCourses: Course[] = [
  { id: 101, code: "CSE301", name: "데이터베이스", professor: "김도윤", department: "컴퓨터공학과", day: "월", time: "09:00–10:50", room: "공학관 301", credits: 3, capacity: 30, enrolled: 29 },
  { id: 102, code: "CSE212", name: "운영체제", professor: "이서준", department: "컴퓨터공학과", day: "화", time: "13:00–14:50", room: "공학관 204", credits: 3, capacity: 40, enrolled: 40 },
  { id: 103, code: "CSE220", name: "컴퓨터 네트워크", professor: "박지우", department: "컴퓨터공학과", day: "수", time: "15:00–16:50", room: "공학관 305", credits: 3, capacity: 35, enrolled: 20 },
  { id: 104, code: "MAT201", name: "이산수학", professor: "정수현", department: "수학과", day: "목", time: "10:00–11:50", room: "자연관 112", credits: 3, capacity: 45, enrolled: 31 },
  { id: 105, code: "CSE401", name: "분산시스템", professor: "최민준", department: "컴퓨터공학과", day: "금", time: "09:00–11:50", room: "공학관 401", credits: 3, capacity: 25, enrolled: 24 },
  { id: 106, code: "BUS110", name: "경영학원론", professor: "한유진", department: "경영학과", day: "월", time: "13:00–14:50", room: "경영관 201", credits: 3, capacity: 60, enrolled: 42 },
  { id: 107, code: "CSE330", name: "클라우드 컴퓨팅", professor: "오세진", department: "컴퓨터공학과", day: "목", time: "15:00–16:50", room: "공학관 402", credits: 3, capacity: 30, enrolled: 18 }
];

export const students: Student[] = [
  { id: 20260001, name: "김지윤" },
  { id: 20260002, name: "이민호" },
  { id: 20260003, name: "박서연" }
];

export const createInitialEnrollments = (): EnrollmentMap => ({
  20260001: [],
  20260002: [103],
  20260003: [104, 106]
});

export const cloneCourses = () => initialCourses.map((course) => ({ ...course }));
