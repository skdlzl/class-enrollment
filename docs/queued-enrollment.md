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
3. 접수 직후 Redis sorted set에 비동기 게시. 누락된 미완료 접수는 5초마다 복구/게시. 접수 ID 순으로 우선 처리하되 엄격한 선착순 확정 보장은 하지 않음.
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
