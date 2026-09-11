const initialCourses = [
  { id: 101, code: "CSE301", name: "데이터베이스", professor: "김도윤", department: "컴퓨터공학과", day: "월", time: "09:00–10:50", room: "공학관 301", credits: 3, capacity: 30, enrolled: 29 },
  { id: 102, code: "CSE212", name: "운영체제", professor: "이서준", department: "컴퓨터공학과", day: "화", time: "13:00–14:50", room: "공학관 204", credits: 3, capacity: 40, enrolled: 40 },
  { id: 103, code: "CSE220", name: "컴퓨터 네트워크", professor: "박지우", department: "컴퓨터공학과", day: "수", time: "15:00–16:50", room: "공학관 305", credits: 3, capacity: 35, enrolled: 20 },
  { id: 104, code: "MAT201", name: "이산수학", professor: "정수현", department: "수학과", day: "목", time: "10:00–11:50", room: "자연관 112", credits: 3, capacity: 45, enrolled: 31 },
  { id: 105, code: "CSE401", name: "분산시스템", professor: "최민준", department: "컴퓨터공학과", day: "금", time: "09:00–11:50", room: "공학관 401", credits: 3, capacity: 25, enrolled: 24 },
  { id: 106, code: "BUS110", name: "경영학원론", professor: "한유진", department: "경영학과", day: "월", time: "13:00–14:50", room: "경영관 201", credits: 3, capacity: 60, enrolled: 42 },
  { id: 107, code: "CSE330", name: "클라우드 컴퓨팅", professor: "오세진", department: "컴퓨터공학과", day: "목", time: "15:00–16:50", room: "공학관 402", credits: 3, capacity: 30, enrolled: 18 }
];
const students = [
  { id: 20260001, name: "김지윤" },
  { id: 20260002, name: "이민호" },
  { id: 20260003, name: "박서연" }
];
const cloneCourses = () => initialCourses.map(course => ({ ...course }));
let courses = cloneCourses();
let currentStudentId = students[0].id;
let enrollments = { [students[0].id]: [], [students[1].id]: [103], [students[2].id]: [104, 106] };

const refs = {
  studentSelect: document.querySelector("#studentSelect"), searchInput: document.querySelector("#searchInput"),
  departmentFilter: document.querySelector("#departmentFilter"), statusFilter: document.querySelector("#statusFilter"),
  courseTable: document.querySelector("#courseTable"), enrollmentList: document.querySelector("#enrollmentList"),
  enrollmentEmpty: document.querySelector("#enrollmentEmpty"), emptyState: document.querySelector("#emptyState"),
  resultCount: document.querySelector("#resultCount"), sidebarCount: document.querySelector("#sidebarCount"),
  creditCount: document.querySelector("#creditCount"), courseCount: document.querySelector("#courseCount"),
  remainingCredits: document.querySelector("#remainingCredits"), toast: document.querySelector("#toast"),
  resetButton: document.querySelector("#resetButton")
};

function init() {
  refs.studentSelect.innerHTML = students.map(s => `<option value="${s.id}">${s.name} · ${s.id}</option>`).join("");
  const departments = [...new Set(courses.map(c => c.department))];
  refs.departmentFilter.innerHTML += departments.map(d => `<option value="${d}">${d}</option>`).join("");
  bindEvents();
  render();
}

function bindEvents() {
  refs.studentSelect.addEventListener("change", event => { currentStudentId = Number(event.target.value); render(); });
  refs.searchInput.addEventListener("input", renderCourses);
  refs.departmentFilter.addEventListener("change", renderCourses);
  refs.statusFilter.addEventListener("change", renderCourses);
  refs.resetButton.addEventListener("click", resetData);
}

function currentEnrollmentIds() { return enrollments[currentStudentId] || []; }

function render() { renderCourses(); renderEnrollments(); renderMetrics(); }

function renderCourses() {
  const keyword = refs.searchInput.value.trim().toLowerCase();
  const department = refs.departmentFilter.value;
  const status = refs.statusFilter.value;
  const selected = currentEnrollmentIds();
  const filtered = courses.filter(course => {
    const keywordMatch = `${course.name} ${course.professor} ${course.code}`.toLowerCase().includes(keyword);
    const departmentMatch = department === "all" || course.department === department;
    const full = course.enrolled >= course.capacity;
    const statusMatch = status === "all" || (status === "open" ? !full : full);
    return keywordMatch && departmentMatch && statusMatch;
  });
  refs.resultCount.textContent = `총 ${filtered.length}개 과목`;
  refs.emptyState.hidden = filtered.length > 0;
  refs.courseTable.innerHTML = filtered.map(course => {
    const isFull = course.enrolled >= course.capacity;
    const isSelected = selected.includes(course.id);
    const percent = Math.min(100, Math.round((course.enrolled / course.capacity) * 100));
    return `<tr>
      <td class="course-title"><strong>${course.name}</strong><span>${course.code} · ${course.department}</span></td>
      <td>${course.professor}</td>
      <td class="time"><strong>${course.day}</strong> ${course.time}<br><span>${course.room}</span></td>
      <td>${course.credits}</td>
      <td class="capacity"><div class="capacity-row"><strong>${course.enrolled}/${course.capacity}</strong><span>${isFull ? "마감" : `${course.capacity - course.enrolled}자리`}</span></div><div class="bar ${isFull ? "full" : ""}"><i style="width:${percent}%"></i></div></td>
      <td><button class="enroll-button ${isSelected ? "cancel" : ""}" data-course-id="${course.id}" ${isFull && !isSelected ? "disabled" : ""}>${isSelected ? "취소" : isFull ? "마감" : "신청"}</button></td>
    </tr>`;
  }).join("");
  refs.courseTable.querySelectorAll("button[data-course-id]").forEach(button => button.addEventListener("click", () => toggleEnrollment(Number(button.dataset.courseId))));
}

function toggleEnrollment(courseId) {
  const selected = currentEnrollmentIds();
  if (selected.includes(courseId)) { cancelEnrollment(courseId); return; }
  const course = courses.find(c => c.id === courseId);
  if (course.enrolled >= course.capacity) { showToast("이미 정원이 마감된 과목입니다.", true); return; }
  const credits = selected.reduce((sum, id) => sum + courses.find(c => c.id === id).credits, 0);
  if (credits + course.credits > 18) { showToast("최대 신청 학점은 18학점입니다.", true); return; }
  const conflict = selected.map(id => courses.find(c => c.id === id)).find(c => c.day === course.day && c.time === course.time);
  if (conflict) { showToast(`${conflict.name} 과목과 시간이 겹칩니다.`, true); return; }
  selected.push(courseId); course.enrolled += 1;
  showToast(`${course.name} 수강신청이 완료되었습니다.`); render();
}

function cancelEnrollment(courseId) {
  const course = courses.find(c => c.id === courseId);
  enrollments[currentStudentId] = currentEnrollmentIds().filter(id => id !== courseId);
  course.enrolled = Math.max(0, course.enrolled - 1);
  showToast(`${course.name} 수강신청을 취소했습니다.`); render();
}

function renderEnrollments() {
  const selectedCourses = currentEnrollmentIds().map(id => courses.find(c => c.id === id)).filter(Boolean);
  refs.sidebarCount.textContent = selectedCourses.length;
  refs.enrollmentEmpty.hidden = selectedCourses.length > 0;
  refs.enrollmentList.innerHTML = selectedCourses.map(course => `<article class="enrollment-item"><div class="enrollment-item-head"><div><strong>${course.name}</strong><span>${course.day} ${course.time} · ${course.credits}학점</span></div><button class="text-button" data-cancel-id="${course.id}">취소</button></div></article>`).join("");
  refs.enrollmentList.querySelectorAll("button[data-cancel-id]").forEach(button => button.addEventListener("click", () => cancelEnrollment(Number(button.dataset.cancelId))));
}

function renderMetrics() {
  const selected = currentEnrollmentIds().map(id => courses.find(c => c.id === id)).filter(Boolean);
  const credits = selected.reduce((sum, course) => sum + course.credits, 0);
  refs.creditCount.textContent = credits; refs.courseCount.textContent = selected.length; refs.remainingCredits.textContent = 18 - credits;
}

function resetData() {
  courses = cloneCourses();
  enrollments = { [students[0].id]: [], [students[1].id]: [103], [students[2].id]: [104, 106] };
  refs.searchInput.value = ""; refs.departmentFilter.value = "all"; refs.statusFilter.value = "all";
  showToast("테스트 데이터를 초기화했습니다."); render();
}

let toastTimer;
function showToast(message, isError = false) {
  clearTimeout(toastTimer); refs.toast.textContent = message; refs.toast.className = `toast show${isError ? " error" : ""}`;
  toastTimer = setTimeout(() => { refs.toast.className = "toast"; }, 2600);
}

function registerAgentTools() {
  const context = document.modelContext;
  if (!context?.registerTool) return;

  const tools = [
    {
      name: "list_courses",
      title: "개설 과목 조회",
      description: "현재 개설된 과목과 정원, 신청 인원을 조회합니다.",
      inputSchema: { type: "object", properties: {}, additionalProperties: false },
      annotations: { readOnlyHint: true, untrustedContentHint: false },
      execute: async () => courses.map(({ id, code, name, professor, capacity, enrolled }) => ({ id, code, name, professor, capacity, enrolled }))
    },
    {
      name: "enroll_course",
      title: "수강신청",
      description: "현재 선택된 학생을 지정한 과목에 수강신청합니다.",
      inputSchema: { type: "object", properties: { courseId: { type: "number" } }, required: ["courseId"], additionalProperties: false },
      annotations: { readOnlyHint: false, untrustedContentHint: false },
      execute: async ({ courseId }) => { if (!courses.some(c => c.id === courseId)) throw new Error("존재하지 않는 과목입니다."); toggleEnrollment(courseId); return { courseId, studentId: currentStudentId, enrolled: currentEnrollmentIds().includes(courseId) }; }
    },
    {
      name: "reset_enrollment_data",
      title: "수강신청 데이터 초기화",
      description: "모든 수강신청 테스트 데이터를 초기 상태로 되돌립니다.",
      inputSchema: { type: "object", properties: {}, additionalProperties: false },
      annotations: { readOnlyHint: false, untrustedContentHint: false },
      execute: async () => { resetData(); return { reset: true }; }
    }
  ];

  tools.forEach(tool => {
    try { Promise.resolve(context.registerTool(tool)).catch(() => {}); } catch (_) {}
  });
}

init();
registerAgentTools();
