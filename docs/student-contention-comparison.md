# 학생 Redis 락의 추가 효과 검증

## 질문과 범위

같은 학생이 서로 다른 과목을 동시에 신청할 때 Redis에서 먼저 대기시키면 DB 학생 락을 기다리는 연결의 점유를 줄이는가?

- DB_ONLY: 학생 DB 락 → 검증 → 과목 DB 락 → 저장 및 커밋
- PESSIMISTIC: 학생 Redis 락 → 위와 동일한 DB 안전장치 → 커밋 후 Redis 락 해제

두 구성 모두 학생 DB 락을 사용한다. Redis TTL 만료 시에도 학점과 시간표를 DB에서 다시 보호한다. Redis를 추가한 비용과 이익을 비교하며 Redis 우위를 전제하지 않는다.

이 실험은 인기 과목 경합을 분리한 진단 실험이다. 학생 100명에게 각자 5개씩 서로 다른 신청 대상 과목을 배정한다(총 500과목). 다른 학생과 과목은 겹치지 않는다. 원래 5개 인기 과목에 500명이 몰리는 실험과 수치를 직접 비교하지 않는다. 동일 학생의 5개 요청은 양 서버에 번갈아 발송한다. 인위적 서버 sleep은 없다.

## 조건

100묶음을 5초에 걸쳐 시작한다. 각 묶음은 목록 2페이지와 과목명 검색을 마친 뒤 5개 POST를 await 없이 함께 발송한다. 첫 묶음 시작 시각부터 모든 POST가 정확히 5초 안에 서버에 도착한다는 뜻은 아니다. 실제 발송 시각과 소켓 대기를 mixed.json에 남긴다. 검색이 실패하면 해당 묶음은 신청하지 않고 미발송으로 보고한다. 재시도는 없다.

검색용 포함 총 1만 과목(100학과 × 100과목)을 새로 생성한다. 기존 데이터는 삭제하지 않아 DB 전체 과목 수는 실행마다 증가한다. 양 구성 순서를 번갈아 실행하고 결과 조건과 누적 데이터 차이를 기록한다.

서버 2개, 서버당 단일 연결 풀 10개, 로컬 게이트 OFF, DB admission OFF, 조회 풀 분리 OFF. 배경 조회 서버당 50RPS, baseline/mixed 각 90초. HTTP 신청 타임아웃은 30초로 양쪽 동일하다. Redis/DB 내부 락 타임아웃 정책은 실제 구현을 그대로 사용하므로 503도 반드시 보고한다.

| 모드 | 학생 | 조건 | 정상 기대 결과 |
|---|---:|---|---|
| valid (기본 성능 비교) | 100명 | 5과목 × 3학점, 최대 18학점, 시간표 겹침 없음 | 500건 저장 |
| valid --control | 500명 | 묶음마다 서로 다른 학생 5명 사용 | 500건 저장 |
| credit | 100명 | 최대 9학점 | 300건 저장 + 학점 거절 200건 |
| schedule | 100명 | 첫 두 과목 시간 겹침, 최대 18학점 | 400건 저장 + 시간표 거절 100건 |

credit/schedule 거절을 성능 성공률에 섞지 않는다. DB에서 신청별 학점 합계, 시간 구간 중복, 중복 신청, 과목별 실제 내역과 카운터를 확인한다. 정상 기대 건수가 맞아도 응답 유실이면 clientDatabaseMatch=false다.

## 실행

기존 Java 두 창을 종료한다. MySQL과 Redis는 유지한다. 최신 backend JAR가 이미 있다면 이번 변경은 스크립트만이므로 재빌드가 필요 없다.

```powershell
cd C:\portfolio\class-enrollment
git switch feat/redis-ingress-queue
git pull --ff-only
powershell -ExecutionPolicy Bypass -File .\scripts\start-student-contention-servers.ps1 PESSIMISTIC
# 양쪽 Started 로그 확인
node .\scripts\run-student-contention.mjs PESSIMISTIC --warmup
node .\scripts\run-student-contention.mjs PESSIMISTIC
```

Java 두 창 종료 후 동일 조건으로 순수 DB를 실행한다.

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\start-student-contention-servers.ps1 DB_ONLY
node .\scripts\run-student-contention.mjs DB_ONLY --warmup
node .\scripts\run-student-contention.mjs DB_ONLY
```

처음에는 두 구성 본 측정 각 1회로 확인한다. 이득이 보이면 구성별 3회 이상 교대 측정하고 아래 대조군도 양쪽 같은 옵션으로 실행한다.

```powershell
# STRATEGY를 현재 서버의 PESSIMISTIC 또는 DB_ONLY로 교체
node .\scripts\run-student-contention.mjs STRATEGY --control
node .\scripts\run-student-contention.mjs STRATEGY --scenario credit
node .\scripts\run-student-contention.mjs STRATEGY --scenario schedule
```

결과 폴더는 jmeter/results/student-contention-... 이다. summary.json과 conditions.json을 비교한다. 원시 mixed.json과 fixture.sql도 보존한다.

## 판정

먼저 500건 발송 및 500건 커밋(valid), 예외 0건, 정합성 통과를 확인한다. 그 뒤 pool.usage 평균과 총합, pool.acquire 평균, studentLockQuery 평균, 연결 대기 수, 모든 신청 응답 P95, 5건 모두 응답까지의 P95, 배경 조회 P95/RPS/생략을 비교한다. phaseTimings는 평균이며 P95가 아니다. studentLockQuery는 SQL 전체 시간으로 순수 락 대기만은 아니다.

공통 pool.usage에는 조회도 들어간다. 배경 조회 실제 건수가 다르면 총 점유 시간만으로 Redis 이득을 주장하지 않는다. transactionBody/Proxy와 studentLockQuery도 함께 본다. 동시 요청이 없는 control에서도 비슷한 차이가 나면 실행 환경 차이를 의심한다. 타임아웃으로 일찍 거절해서 낮아진 P95는 우위로 인정하지 않는다. 같은 학생에 경합이 있을 때만 이득이 나면 그 조건에 한정해 설명한다.
