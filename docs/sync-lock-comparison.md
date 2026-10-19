# 동기 과목 락 비교: MySQL 비관적 락 vs Redisson 분산락

두 방식 모두 POST /api/enrollments에서 최종 201/409 응답을 반환한다.
비동기 접수, 폴링, 묶음 거절은 사용하지 않는다. 기존 비동기 실험 결과와 합산하지 않는다.

## 비교 범위

| 항목 | Redisson | MySQL 비관적 락 |
|---|---|---|
| 학생 보호 | 학생 Redisson 락 | 동일 |
| 학생 검증 | validateForEnrollment | 동일 |
| 과목 보호 | 과목 Redis 락 획득 후 쓰기 트랜잭션 | 쓰기 트랜잭션에서 findByIdForUpdate |
| 정원 확인 및 저장 | completeEnrollment | completeEnrollmentWithPessimisticLock |
| 해제 | 커밋 후 Facade finally | 트랜잭션 종료 |

이 실험은 과목 락을 교체한 비교다. Redis를 전혀 사용하지 않는 구현과의 비교는 아니다.
공통 학생 락과 검증을 유지해 과목 보호 방식의 영향을 확인한다.
두 구현 모두 같은 정원 검사와 saveEnrollment를 사용한다.

## 고정 조건

- 같은 JAR, 서버 8080/8081, MySQL, Redis 6380. Hikari 풀은 서버당 10.
- 로컬 대기열, 사전 정원 검사, 비동기 워커 모두 끔.
- 신청 500건을 5초에 분산 전송, 새 과목 정원 100. 기존 데이터 삭제 없음.
- 조회 서버당 10RPS. baseline/mixed 각각 90초, 신청 HTTP 제한 60초.
- 실행 전 두 서버의 전략과 설정을 검사한다. 다르면 부하를 시작하지 않는다.
- MySQL 행 락 대기 제한과 Redis tryLock 제한은 각각 10초지만 전체 요청 제한과 같지 않다.
  DB 연결 획득 대기는 별도이며 이 차이도 결과에 포함된다.

## 실행

현재 브랜치에서 최신 스크립트를 받는다. 이번 변경은 JS와 문서만 포함하므로 최신 실험용 JAR을 이미 사용 중이면 재빌드할 필요가 없다.

```powershell
cd C:\portfolio\class-enrollment
git switch feat/redis-ingress-queue
git pull --ff-only
```

기존 두 서버를 종료한다. 아래 명령은 backend 폴더에서 실행한다.
첫 번째 창은 8080, 두 번째 창은 8081로 실행한다.
queue 프로필은 Redis 6380 주소를 선택하기 위한 것이며 워커는 명시적으로 끈다.

```powershell
cd C:\portfolio\class-enrollment\backend
java -jar .\target\class-enrollment-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=queue --server.port=8080 --experiment.enabled=true --spring.datasource.hikari.maximum-pool-size=10 --enrollment.queue.enabled=false --enrollment.course-lock-strategy=REDIS --enrollment.local-gate-enabled=false --enrollment.capacity-precheck=false
```

두 서버가 기동되면 별도 창에서 실행한다.

```powershell
cd C:\portfolio\class-enrollment
node .\scripts\run-sync-lock-comparison.mjs REDIS
```

비관적 락은 두 서버를 종료한 뒤 같은 실행 명령에서 REDIS만 PESSIMISTIC으로 바꿔 시작한다.

```powershell
node .\scripts\run-sync-lock-comparison.mjs PESSIMISTIC
```

한 회차는 약 3분과 데이터 준비 시간이 필요하다. 첫 회차는 설정과 측정이 유효한지 확인한다.
최종 비교는 양쪽에 동일한 예열을 적용한 뒤 실행 순서를 교차해 반복한다.
조회 baseline은 조회 예열이며 신청 경로의 예열을 대신하지 않는다.

## 결과 판단

신청 최종 응답 P95, 201/409/503 및 응답 불명 건수, 신청 집중 구간 조회 P95,
DB 연결 대기와 클라이언트 소켓 대기를 함께 확인한다. 연결 풀 표는 주기적 표본이므로 순간 최대치를 보장하지 않는다.
요청 누락이나 응답 불명이 발생한 회차는 정상 처리 속도 우열의 근거로 사용하지 않는다.

Redis의 courseLockWaitMs는 Redis 락 대기다. 비관적 락의 writeMs에는 연결 획득과 행 락 대기가 포함될 수 있으므로 SQL 실행 시간으로 해석하지 않는다.
기존 서버 진단의 submit 카운터는 비동기 접수 API용이다. 이 동기 비교의 처리 건수는 HTTP 결과와 DB 결과를 기준으로 확인한다.

DB 연결 대기와 조회 지연이 줄면서 신청 처리 비용이 증가한다면 트레이드오프가 확인된 것이다.
모든 주요 지표에서 비관적 락이 좋으면 이 조건에서는 비관적 락이 유리하다고 기록한다.
