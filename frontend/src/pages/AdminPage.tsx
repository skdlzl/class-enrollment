import AppHeader from "../components/AppHeader";

export default function AdminPage() {
  return (
    <div className="app-shell admin-shell">
      <AppHeader />
      <main className="admin-page">
        <div className="admin-heading">
          <p className="eyebrow">ADMIN CONSOLE</p>
          <h1>관리자 화면</h1>
          <p>과목 관리와 신청 현황 모니터링 기능을 이 경로에 확장할 수 있도록 라우트를 분리했습니다.</p>
        </div>
        <section className="admin-placeholder card">
          <span className="placeholder-mark">A</span>
          <div>
            <strong>관리자 기능 준비 중</strong>
            <p>다음 단계에서 과목 등록, 정원 변경, 신청 현황 조회 기능을 연결합니다.</p>
          </div>
        </section>
      </main>
    </div>
  );
}
