import { NavLink } from "react-router-dom";
import type { Student } from "../types";

type AppHeaderProps = {
  students?: Student[];
  currentStudentId?: number;
  onStudentChange?: (studentId: number) => void;
  onReset?: () => void;
};

export default function AppHeader({
  students,
  currentStudentId,
  onStudentChange,
  onReset
}: AppHeaderProps) {
  return (
    <header className="topbar">
      <NavLink className="brand" to="/" aria-label="Class Enrollment 홈">
        <span className="brand-mark">CE</span>
        <span>
          <strong>Class Enrollment</strong>
          <small>2026학년도 1학기</small>
        </span>
      </NavLink>

      <nav className="global-nav" aria-label="주요 메뉴">
        <NavLink to="/" end>수강신청</NavLink>
        <NavLink to="/admin">관리자</NavLink>
      </nav>

      {students && currentStudentId !== undefined ? (
        <div className="top-actions">
          <label className="student-switcher">
            <span className="avatar" aria-hidden="true">학</span>
            <span className="student-label">
              <small>현재 학생</small>
              <select
                value={currentStudentId}
                onChange={(event) => onStudentChange?.(Number(event.target.value))}
                aria-label="현재 학생 선택"
              >
                {students.map((student) => (
                  <option key={student.id} value={student.id}>
                    {student.name} · {student.id}
                  </option>
                ))}
              </select>
            </span>
          </label>
          <button className="icon-button" type="button" onClick={onReset} aria-label="테스트 데이터 초기화">
            ↻
          </button>
        </div>
      ) : null}
    </header>
  );
}
