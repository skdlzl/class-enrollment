import { useMemo, useState } from "react";
import AppHeader from "../components/AppHeader";
import { cloneCourses, createInitialEnrollments, students } from "../data/mockData";
import type { Course, EnrollmentMap } from "../types";

type Toast = { message: string; error: boolean } | null;

export default function EnrollmentPage() {
  const [courses, setCourses] = useState(cloneCourses);
  const [enrollments, setEnrollments] = useState<EnrollmentMap>(createInitialEnrollments);
  const [currentStudentId, setCurrentStudentId] = useState(students[0].id);
  const [keyword, setKeyword] = useState("");
  const [department, setDepartment] = useState("all");
  const [status, setStatus] = useState("all");
  const [toast, setToast] = useState<Toast>(null);

  const currentIds = enrollments[currentStudentId] ?? [];
  const courseMap = useMemo(() => new Map(courses.map((course) => [course.id, course])), [courses]);
  const selectedCourses = currentIds.map((id) => courseMap.get(id)).filter((course): course is Course => Boolean(course));
  const currentCredits = selectedCourses.reduce((sum, course) => sum + course.credits, 0);
  const departments = useMemo(() => [...new Set(courses.map((course) => course.department))], [courses]);

  const filteredCourses = useMemo(() => {
    const normalizedKeyword = keyword.trim().toLowerCase();
    return courses.filter((course) => {
      const keywordMatch = `${course.name} ${course.professor} ${course.code}`.toLowerCase().includes(normalizedKeyword);
      const departmentMatch = department === "all" || course.department === department;
      const full = course.enrolled >= course.capacity;
      const statusMatch = status === "all" || (status === "open" ? !full : full);
      return keywordMatch && departmentMatch && statusMatch;
    });
  }, [courses, department, keyword, status]);

  const showToast = (message: string, error = false) => {
    setToast({ message, error });
    window.setTimeout(() => setToast(null), 2600);
  };

  const cancelEnrollment = (courseId: number) => {
    const course = courseMap.get(courseId);
    setEnrollments((current) => ({
      ...current,
      [currentStudentId]: (current[currentStudentId] ?? []).filter((id) => id !== courseId)
    }));
    setCourses((current) => current.map((item) => item.id === courseId ? { ...item, enrolled: Math.max(0, item.enrolled - 1) } : item));
    if (course) showToast(`${course.name} 수강신청을 취소했습니다.`);
  };

  const enrollCourse = (course: Course) => {
    if (currentIds.includes(course.id)) {
      cancelEnrollment(course.id);
      return;
    }
    if (course.enrolled >= course.capacity) {
      showToast("이미 정원이 마감된 과목입니다.", true);
      return;
    }
    if (currentCredits + course.credits > 18) {
      showToast("최대 신청 학점은 18학점입니다.", true);
      return;
    }
    const conflict = selectedCourses.find((selected) => selected.day === course.day && selected.time === course.time);
    if (conflict) {
      showToast(`${conflict.name} 과목과 시간이 겹칩니다.`, true);
      return;
    }

    setEnrollments((current) => ({
      ...current,
      [currentStudentId]: [...(current[currentStudentId] ?? []), course.id]
    }));
    setCourses((current) => current.map((item) => item.id === course.id ? { ...item, enrolled: item.enrolled + 1 } : item));
    showToast(`${course.name} 수강신청이 완료되었습니다.`);
  };

  const resetData = () => {
    setCourses(cloneCourses());
    setEnrollments(createInitialEnrollments());
    setKeyword("");
    setDepartment("all");
    setStatus("all");
    showToast("테스트 데이터를 초기화했습니다.");
  };

  return (
    <div className="app-shell">
      <AppHeader
        students={students}
        currentStudentId={currentStudentId}
        onStudentChange={setCurrentStudentId}
        onReset={resetData}
      />

      <main className="page-content">
        <section className="summary" aria-labelledby="page-title">
          <div>
            <p className="eyebrow">ENROLLMENT STATUS</p>
            <h1 id="page-title">수강신청</h1>
            <p>과목을 검색하고 남은 정원과 내 시간표를 함께 확인하세요.</p>
          </div>
          <div className="metrics">
            <article><span>신청 학점</span><strong>{currentCredits} / 18</strong></article>
            <article><span>신청 과목</span><strong>{selectedCourses.length}개</strong></article>
            <article><span>남은 학점</span><strong>{18 - currentCredits}학점</strong></article>
          </div>
        </section>

        <div className="workspace">
          <section className="catalog card" aria-labelledby="catalog-title">
            <div className="section-head">
              <div><p className="eyebrow">COURSE CATALOG</p><h2 id="catalog-title">개설 과목</h2></div>
              <span className="result-count">총 {filteredCourses.length}개 과목</span>
            </div>

            <div className="filters">
              <label className="search-box">
                <span aria-hidden="true">⌕</span>
                <input value={keyword} onChange={(event) => setKeyword(event.target.value)} type="search" placeholder="과목명, 교수명으로 검색" />
              </label>
              <select value={department} onChange={(event) => setDepartment(event.target.value)} aria-label="학과 필터">
                <option value="all">전체 학과</option>
                {departments.map((item) => <option key={item} value={item}>{item}</option>)}
              </select>
              <select value={status} onChange={(event) => setStatus(event.target.value)} aria-label="신청 가능 여부 필터">
                <option value="all">전체 상태</option>
                <option value="open">신청 가능</option>
                <option value="closed">마감</option>
              </select>
            </div>

            <div className="table-wrap">
              <table>
                <thead><tr><th>과목 정보</th><th>담당 교수</th><th>시간</th><th>학점</th><th>신청 현황</th><th><span className="sr-only">신청</span></th></tr></thead>
                <tbody>
                  {filteredCourses.map((course) => {
                    const full = course.enrolled >= course.capacity;
                    const selected = currentIds.includes(course.id);
                    const percent = Math.min(100, Math.round((course.enrolled / course.capacity) * 100));
                    return (
                      <tr key={course.id}>
                        <td className="course-title"><strong>{course.name}</strong><span>{course.code} · {course.department}</span></td>
                        <td>{course.professor}</td>
                        <td className="time"><strong>{course.day}</strong> {course.time}<br /><span>{course.room}</span></td>
                        <td>{course.credits}</td>
                        <td className="capacity">
                          <div className="capacity-row"><strong>{course.enrolled}/{course.capacity}</strong><span>{full ? "마감" : `${course.capacity - course.enrolled}자리`}</span></div>
                          <div className={`bar ${full ? "full" : ""}`}><i style={{ width: `${percent}%` }} /></div>
                        </td>
                        <td><button className={`enroll-button ${selected ? "cancel" : ""}`} type="button" disabled={full && !selected} onClick={() => enrollCourse(course)}>{selected ? "취소" : full ? "마감" : "신청"}</button></td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
              {filteredCourses.length === 0 ? <div className="empty-state"><strong>검색 결과가 없습니다.</strong><span>검색어나 필터를 변경해 보세요.</span></div> : null}
            </div>
          </section>

          <aside className="enrollments card" aria-labelledby="schedule-title">
            <div className="section-head">
              <div><p className="eyebrow">MY SCHEDULE</p><h2 id="schedule-title">내 신청 과목</h2></div>
              <span className="badge">{selectedCourses.length}</span>
            </div>
            {selectedCourses.length > 0 ? (
              <div className="enrollment-list">
                {selectedCourses.map((course) => (
                  <article className="enrollment-item" key={course.id}>
                    <div><strong>{course.name}</strong><span>{course.day} {course.time} · {course.credits}학점</span></div>
                    <button className="text-button" type="button" onClick={() => cancelEnrollment(course.id)}>취소</button>
                  </article>
                ))}
              </div>
            ) : (
              <div className="sidebar-empty"><span className="empty-icon">＋</span><strong>신청한 과목이 없습니다.</strong><p>왼쪽 목록에서 원하는 과목을 신청해 주세요.</p></div>
            )}
            <div className="notice"><strong>신청 안내</strong><p>최대 18학점까지 신청할 수 있으며 시간이 겹치는 과목은 동시에 신청할 수 없습니다.</p></div>
          </aside>
        </div>
      </main>

      <div className={`toast ${toast ? "show" : ""} ${toast?.error ? "error" : ""}`} role="status" aria-live="polite">{toast?.message}</div>
    </div>
  );
}
