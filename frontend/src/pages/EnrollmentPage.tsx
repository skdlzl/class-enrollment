import { useEffect, useRef, useState } from "react";
import AppHeader from "../components/AppHeader";

type Student = { id: number; name: string; maxCredits: number };
type Course = { id: number; code: string; name: string; professor: string; department: string; credits: number; capacity: number; enrolled: number };
type Schedule = { courseId: number; day: string; start: string; end: string };
type Ticket = { requestId: string; studentId: number; courseId: number; courseName: string; status: "QUEUED" | "PROCESSING" | "CONFIRMED" | "REJECTED" | "FAILED"; message: string; createdAt: string };
type Options = { students: Student[]; courses: Course[]; schedules: Schedule[] };
type Snapshot = { tickets: Ticket[]; enrollments: { id: number; name: string; credits: number }[] };
const labels = { QUEUED: "접수 완료 · 대기 중", PROCESSING: "신청 확인 중", CONFIRMED: "신청 확정", REJECTED: "신청 불가", FAILED: "재시도 필요" };
const days: Record<string,string> = { MONDAY:"월", TUESDAY:"화", WEDNESDAY:"수", THURSDAY:"목", FRIDAY:"금", SATURDAY:"토", SUNDAY:"일" };
async function api<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, { ...init, signal: init?.signal ? AbortSignal.any([init.signal, AbortSignal.timeout(10000)]) : AbortSignal.timeout(10000) });
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.message || "서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.");
  }
  return response.json() as Promise<T>;
}
export default function EnrollmentPage() {
  const [options, setOptions] = useState<Options>({ students:[], courses:[], schedules:[] });
  const [studentId,setStudentId] = useState(() => {
    try { return Number(localStorage.getItem("enrollment-student-v1")) || 0; } catch { return 0; }
  });
  const [snapshot,setSnapshot] = useState<Snapshot>({tickets:[],enrollments:[]});
  const [keyword,setKeyword] = useState("");
  const [department,setDepartment] = useState("all");
  const [error,setError] = useState("");
  const [loading,setLoading] = useState(true);
  const [submitting,setSubmitting] = useState<number[]>([]);
  const [refresh,setRefresh] = useState(0);
  const selectedStudent = useRef(studentId);
  const inflight = useRef(new Set<string>());
  const snapshotVersion = useRef(0);
  selectedStudent.current=studentId;
  useEffect(()=>{try {if(studentId)localStorage.setItem("enrollment-student-v1",String(studentId));}catch{/* storage optional */}},[studentId]);
  useEffect(() => {
    const controller=new AbortController();
    setLoading(true);
    api<Options>("/api/enrollment-requests/options",{signal:controller.signal})
      .then(data => {
        setOptions(data);setError("");
        setStudentId(current => data.students.some(s=>s.id===current) ? current : data.students[0]?.id ?? 0);
      }).catch(e=>{if(!controller.signal.aborted)setError(e.message);})
      .finally(()=>{if(!controller.signal.aborted)setLoading(false);});
    return ()=>controller.abort();
  },[refresh]);
  useEffect(() => {
    if(!studentId) return;
    let disposed=false;
    let timer: ReturnType<typeof setTimeout>;
    const controller=new AbortController();
    setSnapshot({tickets:[],enrollments:[]});setSubmitting([]);
    let initial=true;
    async function poll() {
      const version=snapshotVersion.current;
      try {
        const data=await api<Snapshot>(`/api/enrollment-requests/snapshot?studentId=${studentId}&includeEnrollments=${initial}`,{signal:controller.signal});
        if(!disposed && version===snapshotVersion.current) {
          setSnapshot(current=>({tickets:data.tickets,enrollments:[...new Map([...current.enrollments,...data.enrollments].map(e=>[e.id,e])).values()]}));
          initial=false;setError("");
        }
      } catch(e) {if(!disposed)setError(e instanceof Error ? e.message : "결과를 불러오지 못했습니다.");}
      finally {if(!disposed)timer=setTimeout(poll,2000);}
    }
    void poll();
    return ()=>{disposed=true;controller.abort();clearTimeout(timer);};
  },[studentId,refresh]);
  async function submit(courseId: number, retryId?: string) {
    const owner=studentId,key=`${owner}:${courseId}`;
    if(inflight.current.has(key))return;
    inflight.current.add(key);setSubmitting(current=>[...current,courseId]);
    try {
      const ticket=await api<Ticket>(retryId ? `/api/enrollment-requests/${retryId}/retry` : "/api/enrollment-requests",{
        method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({studentId:owner,courseId})
      });
      if(selectedStudent.current===owner) {
        snapshotVersion.current++;
        setSnapshot(current=>({...current,tickets:[ticket,...current.tickets.filter(t=>t.requestId!==ticket.requestId)]}));setError("");
      }
    } catch(e) {
      if(selectedStudent.current===owner)setError(`${e instanceof Error ? e.message : "접수 응답을 확인하지 못했습니다."} 접수된 경우 아래 목록에 자동으로 표시됩니다.`);
    } finally {
      inflight.current.delete(key);
      if(selectedStudent.current===owner)setSubmitting(current=>current.filter(id=>id!==courseId));
    }
  }
  const student=options.students.find(s=>s.id===studentId);
  const credits=snapshot.enrollments.reduce((sum,c)=>sum+c.credits,0);
  const pending=snapshot.tickets.filter(t=>t.status==="QUEUED" || t.status==="PROCESSING");
  const courses=options.courses.filter(c=>(department==="all" || c.department===department) && `${c.name} ${c.code} ${c.professor}`.toLowerCase().includes(keyword.toLowerCase()));
  return <div className="app-shell">
    <AppHeader students={options.students} currentStudentId={studentId} onStudentChange={setStudentId}/>
    <main className="page-content">
      <section className="summary">
        <div><p className="eyebrow">COURSE ENROLLMENT</p><h1>수강신청</h1><p>한 번 접수하면, 신청 결과까지 여기서 확인할 수 있어요.</p></div>
        <div className="metrics"><article><span>확정 학점</span><strong>{credits} / {student?.maxCredits ?? 18}</strong></article><article><span>확정 과목</span><strong>{snapshot.enrollments.length}개</strong></article><article><span>처리 대기</span><strong>{pending.length}개</strong></article></div>
      </section>
      <section className="queue-guide" aria-label="신청 과정">
        <div><b>01</b><span><strong>신청 접수</strong><small>신청 버튼은 한 번만</small></span></div><i aria-hidden="true">→</i>
        <div><b>02</b><span><strong>대기 및 확인</strong><small>학점, 시간표, 정원 확인</small></span></div><i aria-hidden="true">→</i>
        <div><b>03</b><span><strong>결과 확인</strong><small>확정 또는 신청 불가 안내</small></span></div>
      </section>
      <p className="queue-note">접수는 수강 확정이 아닙니다. 창을 닫아도 접수는 유지되며, 다시 접속해 결과를 확인할 수 있습니다.</p>
      {error ? <div className="queue-error" role="alert">{error}<button onClick={()=>setRefresh(v=>v+1)}>다시 연결</button></div> : null}
      <div className="workspace">
        <section className="catalog card">
          <div className="section-head"><div><p className="eyebrow">COURSE CATALOG</p><h2>개설 과목</h2></div><button className="text-button" onClick={()=>setRefresh(v=>v+1)}>목록 새로고침</button></div>
          <div className="filters queue-filters"><label className="search-box"><span aria-hidden="true">⌕</span><input aria-label="과목 검색" type="search" placeholder="과목명, 교수명으로 검색" value={keyword} onChange={e=>setKeyword(e.target.value)}/></label><select aria-label="학과" value={department} onChange={e=>setDepartment(e.target.value)}><option value="all">전체 학과</option>{[...new Set(options.courses.map(c=>c.department))].map(d=><option key={d}>{d}</option>)}</select></div>
          <div className="table-wrap"><table><thead><tr><th>과목 정보</th><th>강의 시간</th><th>학점</th><th>정원</th><th>신청</th></tr></thead><tbody>
            {courses.map(c=>{
              const ticket=snapshot.tickets.find(t=>t.courseId===c.id);
              const confirmed=snapshot.enrollments.some(e=>e.id===c.id);
              const busy=submitting.includes(c.id);
              const schedule=options.schedules.filter(s=>s.courseId===c.id);
              return <tr key={c.id}><td className="course-title"><strong>{c.name}</strong><span>{c.code} · {c.professor}<br/>{c.department}</span></td><td>{schedule.length ? schedule.map((s,i)=><div key={i}>{days[s.day] ?? s.day} {s.start.slice(0,5)}–{s.end.slice(0,5)}</div>) : "시간 미등록"}</td><td>{c.credits}</td><td>{c.capacity}명</td><td><button className="enroll-button" disabled={busy || confirmed || (!!ticket && ticket.status!=="FAILED")} onClick={()=>void submit(c.id,ticket?.status==="FAILED" ? ticket.requestId : undefined)}>{busy ? "접수 중…" : confirmed ? "확정" : ticket ? ticket.status==="FAILED" ? "재시도" : ticket.status==="REJECTED" ? "신청 불가" : "접수됨" : "신청"}</button></td></tr>;
            })}
          </tbody></table>{loading || !courses.length ? <div className="empty-state">{loading ? "과목을 불러오는 중입니다." : "표시할 과목이 없습니다."}</div> : null}</div>
          <p className="catalog-footnote">현재 학기 과목을 최대 100개 표시합니다. 신청 가능 여부는 최종 처리 시 확인합니다.</p>
        </section>
        <aside className="enrollments card">
          <div className="section-head"><div><p className="eyebrow">MY REQUESTS</p><h2>신청 진행 상황</h2></div><span className="live-label">자동 갱신</span></div>
          <div className="request-list" aria-live="polite" aria-relevant="additions text">
            {snapshot.tickets.length ? snapshot.tickets.map(t=><article className={`request-card state-${t.status.toLowerCase()}`} key={t.requestId}>
              <div className="request-title"><strong>{t.courseName || options.courses.find(c=>c.id===t.courseId)?.name || "신청 과목"}</strong><span className="request-state">{labels[t.status]}</span></div>
              <p>{t.message}</p><small>접수 {new Date(t.createdAt).toLocaleTimeString("ko-KR",{hour:"2-digit",minute:"2-digit"})} · {t.requestId.slice(0,8)}</small>
              {t.status==="QUEUED" || t.status==="PROCESSING" ? <div className="request-progress" aria-hidden="true"><i/></div> : null}
              {t.status==="FAILED" ? <button className="text-button" disabled={submitting.includes(t.courseId)} onClick={()=>void submit(t.courseId,t.requestId)}>다시 처리 요청</button> : null}
            </article>) : <div className="sidebar-empty"><strong>접수한 신청이 없습니다.</strong><p>원하는 과목의 신청 버튼을 눌러 주세요.</p></div>}
          </div>
          <div className="notice"><strong>확정된 내 과목</strong>{snapshot.enrollments.length ? snapshot.enrollments.map(c=><p key={c.id}>{c.name} · {c.credits}학점</p>) : <p>확정된 과목이 여기에 표시됩니다.</p>}</div>
        </aside>
      </div>
      <p className="demo-caption">체험용 화면 · 학생을 선택해 신청할 수 있습니다. 관리자 화면은 별도의 UI 데모입니다.</p>
    </main>
  </div>;
}
