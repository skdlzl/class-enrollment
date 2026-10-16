# Redis 접수 방식으로 다시 시작

브랜치: feat/redis-ingress-queue. 기존 feat/queued-enrollment는 비교 기록으로 보존합니다.

## 처리 경로

1. POST 접수: Redis Lua 한 번으로 중복 확인, 접수 상태 저장, 대기열 등록. DB 접근 없음.
2. GET 진행 상태: Redis 조회. 학생 선택 시 기존 확정 과목만 DB에서 한 번 불러옵니다.
3. 워커: 두 서버 전체에서 Redis 분산 슬롯 락으로 최대 2개 DB 작업을 허용합니다. 서버당 워커는 1개입니다.
4. DB 트랜잭션: 접수 결과 행, 학생, 과목 순서로 잠그고 검증 후 저장합니다. 신청 내역, 인원, 최종 결과는 함께 커밋합니다.
5. 커밋 후 Redis에 최종 상태를 반영합니다. 결과가 늦어도 접수 응답을 수강 확정으로 표시하지 않습니다.

기존 과목 분할 큐, DB 접수 저장, 반복 복구 스캔, 단계별 타이밍 로거는 제거했습니다.
Redis 분산락의 역할은 DB 작업량 제한입니다. 정원·학생 불변식의 최종 방어는 DB 행 잠금입니다.
빠른 접수·조회가 목표이며 아직 실제 성능 우위는 측정하지 않았습니다.

## 재처리와 한계

- Redis Lua로 동일 학생/과목의 중복 접수를 하나의 접수 번호로 합칩니다.
- 처리 임대는 30초입니다. 워커가 종료되면 임대가 지난 요청을 다른 워커가 다시 가져갑니다.
- DB 커밋 후 Redis 갱신 전에 중단되어도 DB 결과 기록을 읽어 재전송합니다.
- 임대마다 토큰을 바꾸며 오래된 워커의 Redis 결과 덮어쓰기를 거절합니다.
- DB 자체도 요청 결과 행과 학생/과목 행을 잠가 중복 실행을 방어합니다.
- 일시 오류는 5~30초 지연 후 재시도합니다. 현재 자동 재시도 횟수 제한·관리자 처리 UI는 없으므로 장기 장애는 대기 상태로 남습니다.
- 대기 접수는 최대 5,000건이며 초과 시 429입니다. 정원 마감은 접수 후 REJECTED입니다.
- Redis가 접수를 받지 못하면 HTTP 성공을 반환하지 않습니다. 네트워크 응답 유실 시 같은 학생/과목으로 재조회/재접수합니다.
- 새 Redis는 AOF appendfsync always와 영속 볼륨을 사용합니다. 단일 Redis/호스트이며 볼륨 유실까지 보호하는 HA 구성은 아닙니다. 미처리 Redis 데이터 유실 시 DB만으로 복원할 수 없습니다.
- 접수/결과 보관 TTL은 아직 없으므로 장기 운영에는 보관 정책이 필요합니다. 무제한 운영용 완성품은 아닙니다.
- 기존 프로젝트의 학생 선택 방식은 인증 없는 데모입니다. 운영에서는 인증된 학생 ID를 사용해야 합니다.
- 정확한 전역 선착순은 보장하지 않습니다.

## 실행

두 기존 서버를 종료합니다. 기존 MySQL 및 Redis(6379)는 삭제하지 않습니다.

```powershell
cd C:\portfolio\class-enrollment
git switch feat/redis-ingress-queue
git pull --ff-only
docker compose -f compose.redis-ingress.yml up -d
cd backend
.\mvnw.cmd clean package -DskipTests
if ($LASTEXITCODE -ne 0) { throw "빌드 실패" }
java -jar .\target\class-enrollment-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=queue --server.port=8080
```

별도 창에서 같은 JAR을 8081로 실행합니다. 시작 로그는 `QUEUE_VERSION redis-ingress-v1`입니다.
프론트엔드는 기존처럼 frontend에서 `npm run dev`로 실행합니다.

## 검증 순서

먼저 통합 테스트에서 DB 없는 접수/조회, 중복 접수, 워커 중단 후 재처리, 커밋 후 ACK 유실,
정원 초과 방지, 학생 학점 초과 방지, 롤백을 검증합니다.
그 후 기존과 같은 500건/5초, 정원100, 조회 서버당10RPS로 비교합니다.

```powershell
node .\scripts\run-queue-experiment.mjs
```

스크립트는 양쪽 서버 모드를 확인하고 미완료 Redis 요청이 있으면 중단합니다.
DB 최종 확인 대상은 새 enrollment_ingress_results입니다. 기존 데이터는 삭제하지 않습니다.
접수 지연, 최종 결과 지연, 조회 지연을 별도로 판단하며 성공한 요청만으로 전체 성능을 주장하지 않습니다.
