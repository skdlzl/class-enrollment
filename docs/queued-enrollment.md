# 접수 → 대기 → 수강 확정

`feat/queued-enrollment`는 동기 처리 비교 실험과 분리한 신청 흐름입니다.
브라우저 가상 신청을 실제 DB/Redis 기반 접수로 교체했습니다.

## 실행 (Windows PowerShell)

기존 8080/8081 서버를 종료한 뒤, **모든 서버를 queue 프로필**로 실행합니다.

```powershell
cd C:\portfolio\class-enrollment
git switch feat/queued-enrollment
git pull --ff-only
docker compose up -d
cd backend
.\mvnw.cmd clean package -DskipTests
java -jar .\target\class-enrollment-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=queue --server.port=8080
```

새 터미널에서 두 번째 서버(선택):

```powershell
cd C:\portfolio\class-enrollment\backend
java -jar .\target\class-enrollment-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=queue --server.port=8081
```

프론트:

```powershell
cd C:\portfolio\class-enrollment\frontend
npm ci
npm run dev
```

http://localhost:5173 접속. Vite는 API를 8080으로 전달합니다. 8081도 Redis에서 일을 가져가 처리합니다.
`queue` 프로필을 생략하면 기존 동기 API만 사용합니다. 기존 부하 테스트는 queue 프로필에서 실행하지 마세요.
Flyway V4는 접수 테이블만 추가하며 기존 신청 데이터를 삭제하지 않습니다.
기존 부하 테스트 fixture가 전체 학생/과목을 삭제하려면 접수 테이블을 먼저 비워야 합니다. 실제 접수가 있는 DB에서 초기화 스크립트를 실행하지 마세요.

## 처리 구조

1. POST `/api/enrollment-requests`에 `{studentId,courseId}` 전송.
2. DB에 접수 기록이 커밋되면 HTTP 202와 requestId 반환. **신청 확정이 아님**.
3. 접수 직후 Redis sorted set에 비동기 게시. 누락된 미완료 접수는 5초마다 복구/게시. Redis 시각 기준 처리 가능 시점이 된 요청을 꺼내며 엄격한 선착순 확정 보장은 하지 않음.
4. 서버별 워커 2개. Redis 슬롯 락으로 전체 서버의 동시 처리 수를 기본 4개로 제한.
5. 요청 → 학생 → 과목 Redis 락 획득. DB 트랜잭션에서 접수 → 학생 → 과목 행을 잠그고 최종 검증.
6. 신청 저장/정원 증가/접수 CONFIRMED를 **같은 트랜잭션으로 커밋**.
7. UI는 2초마다 `/snapshot?studentId=...`를 조회. 새로고침/학생 전환 후에도 DB 상태로 복원.

Redis 슬롯 수는 모든 서버에서 동일하게 설정해야 합니다. 슬롯은 Watchdog 기반으로 서버 중단 시 만료됩니다.
락 만료/네트워크 단절 시 슬롯 제한이 일시적으로 겹칠 수 있으므로 하드 리소스 한도는 아닙니다.
최종 DB 행 락은 만료된 Redis 락 소유자의 중복 실행에서도 정원/학점 검증을 보호합니다.
이 구현은 **Redis 처리량 조율 + DB 정합성 방어**이며 Redis 단독 락 성능 비교 실험이 아닙니다.

## 상태와 복구

- QUEUED: 접수, 아직 확정되지 않음.
- PROCESSING: 조건 확인/저장 중.
- CONFIRMED: DB 저장 완료. 재전달되어도 다시 저장하지 않음.
- REJECTED: 정원/학점/시간표/재학 조건 위반. 거절 이유 표시.
- FAILED: 일시 장애 자동 재시도 5회 소진. `/requestId/retry`로 재시도.

미완료 접수는 Redis 유실 후에도 DB에서 재게시합니다. 처리 중 서버가 죽으면 60초 복구 대기 후 다시 시도합니다.
DB 저장 실패 시 신청/정원/확정 표시를 모두 롤백합니다. DB 커밋 후 Redis 해제 실패는 성공을 실패로 바꾸지 않습니다.
같은 학생·과목의 POST는 같은 접수 ID를 반환합니다. 이미 DB에 신청되어 있으면 기존 신청으로 확정 표시합니다.
학생별 접수는 최근 100개 표시. 현 학기 과목/체험 학생은 최대 100개 표시.

## UI 범위

접수 완료, 처리 중, 확정, 신청 불가, 일시 실패를 표시합니다. 접수 중 이중 클릭을 막고 응답 유실 때도 재조회합니다.
엄격한 순번/예상 시간은 보장할 수 없으므로 표시하지 않습니다. 취소 기능은 이 변경에서 제공하지 않습니다.
학생 선택은 기존 프로젝트와 같은 **인증 없는 로컬 데모**입니다. 운영 서비스에는 인증 사용자에서 studentId를 구하고 접수 소유권을 검증해야 합니다.
관리자 화면은 기존 브라우저 가상 데이터 데모로 유지됩니다.

## 검증

```powershell
cd C:\portfolio\class-enrollment\backend
.\mvnw.cmd -Dtest=EnrollmentQueueIntegrationTest test
```
Docker 필요. 중복 접수, Redis 큐 유실 복구, 처리 중 중단 복구, 정원 거절, DB 롤백,
분리된 Redis 클라이언트의 슬롯 제한, 재시도 소진, Redis 조율 없이 겹친 DB 쓰기의 정원 방어를 검증합니다.
성능 실험은 접수 지연과 **접수부터 최종 확정까지**를 따로 측정하고, 조회 API 지연/거절/실패/미완료 수도 함께 집계해야 합니다.
현재 처리량은 측정 전이며 기존 동기 API보다 빠르다고 주장하지 않습니다.

## 첫 부하 실험: 접수 시간과 최종 결과 시간을 분리

기존 catalog 실험용 CS 과목 데이터가 있는 DB와 queue 프로필의 8080/8081 서버를 사용합니다.
Node 18 이상(프론트 개발에 사용하는 Node), Docker CLI 필요. JMeter 추가 설정 없이 실행합니다.

```powershell
cd C:\portfolio\class-enrollment
git pull --ff-only
node .\scripts\run-queue-experiment.mjs
```

약 2분 동안 baseline(조회만) 60초, mixed(조회 + 신청) 60초를 한 번씩 측정합니다.
각 서버에 조회 초당 10건, mixed 시작 5초 후부터 신청 500건을 5초에 걸쳐 보내며 과목 정원은 100명입니다.
수신 응답은 바로 수강 성공으로 세지 않습니다. 접수 ID를 2초마다 조회해 확정/거절/실패를 구분합니다.
최종 결과를 45초 안에 확인하지 못하면 UNRESOLVED입니다. 서버 처리는 계속될 수 있습니다.

- 접수 P95: 202 응답을 받기까지의 시간.
- 확정/거절별 P95: 최초 신청부터 최종 결과를 **클라이언트가 확인**하기까지. DB 실제 완료시각보다 폴링 간격만큼 늦게 관찰될 수 있습니다.
- 조회 P95: 두 단계 모두 같은 5~55초 구간에서 시작한 조회. 실패와 실제 발생률도 함께 표시.
- 상태 분포: CONFIRMED, REJECTED, FAILED, UNRESOLVED, SUBMIT_FAILED, SUBMIT_UNKNOWN.
- DB 검증: enrolled_count = 실제 신청 수 = 확정 접수 수, 정원 이내인지 확인.

`jmeter/results/queue-...`에 조건, 전용 데이터 SQL, 접수 목록, 전체 측정 원본, 집계, DB 상태를 저장합니다.
실험마다 새로운 학생/과목을 추가합니다. 기존 데이터 삭제나 초기화는 하지 않습니다.
미완료 접수가 있으면 다음 실행을 막습니다. 기존 catalog 데이터가 없으면 안전하게 중단합니다.

작은 규모로 확인하려면 다음 명령을 사용합니다(측정 구간은 동일).

```powershell
node .\scripts\run-queue-experiment.mjs --users 100 --capacity 20
```

이 실험은 현재 비동기 구조 안에서 조회 부하의 영향을 확인하는 첫 측정입니다.
기존 JMeter 동기 실험과는 생성기/측정 방식이 달라 숫자를 직접 연결해 개선율을 주장하지 않습니다.
조회 실패, 생성기 지연, 미완료 요청이 있으면 먼저 원인을 확인합니다. 성능 우위가 나오도록 조건을 바꾸지 않습니다.

## 워커 대기 병목 수정

- 작업 후 건별 250ms 휴식을 제거. 처리 가능한 요청이 있으면 계속 처리하고 큐가 비거나 슬롯이 없을 때만 50ms 대기합니다.
- 한 번에 최대 32건 또는 100ms 처리 후 공유 슬롯을 반납해 다른 서버에도 기회를 줍니다.
- 학생/과목/접수 락 충돌 시 Redis 시각 기준 50~150ms 뒤 재시도합니다. 5초 DB 복구 스캔을 정상 재시도 경로로 사용하지 않습니다.
- Lua로 처리 가능 시점 확인과 큐에서 제거를 원자적으로 수행합니다. Redis 서버 시각을 사용해 애플리케이션 서버 시계 차이를 피합니다.
- DB에서 정원 마감이 확인되고 기존 신청도 없으면 학생/과목 Redis 락을 기다리지 않고 거절합니다. 빈자리로 조회되더라도 최종 저장 트랜잭션에서 다시 검증합니다.
- 이미 신청한 학생은 빠른 거절 대상에서 제외합니다. 기존 신청과 접수 상태를 확정으로 맞춥니다.
- QUEUE_PROGRESS 로그의 handled/lockDeferred/fastRejected는 서버 시작 이후 누적 카운터입니다.

**업데이트 시 두 서버를 모두 종료한 뒤 새 JAR로 재시작하세요.** 큐 v2는 처리 가능 시각을 점수로 사용합니다.
기존 접수는 DB 복구가 새 큐에 다시 넣습니다. 구버전과 신버전을 동시에 실행하지 마세요.
45초 관찰 한도, 슬롯 4개, 워커 2개/서버, 요청 수 등 실험 조건은 그대로 유지합니다.
