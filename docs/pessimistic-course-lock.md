# 과목 비관적 락 비교 실험

## 범위

기준: `feat/reduce-course-lock-scope`의 `407ddbf1916e0f4bb9a84e82d05cde5ac48b8d6f`.
학생 Redisson 락과 학생 검증은 유지하고 과목 정원 제어만 교체한다.
**Redis를 완전히 제거한 DB-only 구현이 아니다.** 양쪽 모두 로컬 대기열은 없다.

- PESSIMISTIC (이 브랜치 기본값): 학생 Redis 락 → 학생 검증 트랜잭션 종료 → 별도 쓰기 트랜잭션에서 과목 `SELECT ... FOR UPDATE` → 정원 확인 → 신청 INSERT → 인원 UPDATE → 커밋 → 학생 락 해제.
- REDIS (대조군): 학생 Redis 락 → 학생 검증 → 과목 Redis 락 → 쓰기 트랜잭션 → 커밋 → 과목 락 및 학생 락 해제.

과목을 일반 조회한 엔티티를 그대로 잠그지 않고, 쓰기 트랜잭션에서 처음부터 잠금 조회한다. 신청 INSERT의 외래키 검사보다 먼저 과목 행을 잠근다. DB 락은 수동 unlock하지 않으며 커밋/롤백으로 해제된다. 모든 신청 쓰기는 이 규약을 따라야 한다.

## 실행 (Windows PowerShell)

프로젝트 루트에서 MySQL과 Redis를 실행하고 같은 JAR로 두 JVM을 기동한다.

```powershell
docker compose up -d mysql redis
cd backend
.\mvnw.cmd -DskipTests package
java -jar .\target\class-enrollment-backend-0.0.1-SNAPSHOT.jar --server.port=8080 --enrollment.course-lock-strategy=PESSIMISTIC
```

두 번째 터미널의 backend 폴더:

```powershell
java -jar .\target\class-enrollment-backend-0.0.1-SNAPSHOT.jar --server.port=8081 --enrollment.course-lock-strategy=PESSIMISTIC
```

대조군은 **두 서버 모두 종료한 뒤** 같은 명령의 `PESSIMISTIC`을 `REDIS`로 바꿔 재시작한다. 비교 중 두 방식을 섞어서 실행하지 않는다. 환경 변수 `COURSE_LOCK_STRATEGY`로도 선택 가능하며 알 수 없는 값은 시작 단계에서 실패한다.

## 테스트

```powershell
.\mvnw.cmd "-Dtest=EnrollmentServiceTest,EnrollmentRedissonFacadeTest,EnrollmentLockScopeExperimentTest,EnrollmentPessimisticFacadeTest,EnrollmentPessimisticIntegrationTest" test
```

통합 테스트는 Docker가 필요하며 Testcontainers의 별도 MySQL 8.4 / Redis 7.4를 사용한다. 사용자 로컬 DB 데이터는 초기화하지 않는다.

검증 항목:
- 학생 Redis 락만 사용하고 과목 Redis 락은 호출하지 않음 (Mock)
- DB 잠금 실패 시 503, 학생 락 해제 (Mock)
- 독립 Redis 클라이언트 및 Facade에서 20요청 / 정원5: 성공5, 마감15, DB 두 수치5 (MySQL)
- 같은 학생의 다른 두 과목 동시 신청: 최대 학점 보호 (MySQL/Redis)
- INSERT/UPDATE flush 후 예외 주입: 전체 롤백과 다음 신청 성공 (MySQL)
- 실제 과목 행 잠금 충돌: HTTP 503 및 해제 후 같은 학생의 HTTP 201 (MySQL/Redis)

독립 클라이언트 테스트는 한 JVM 안의 테스트이며 실제 두 서버 HTTP 부하 실험과 구분한다. flush 이후 오류 주입은 실제 COMMIT 통신 장애를 재현한 것은 아니다.

## 시간과 오류 해석

- 학생 Redis 락 획득 대기: 10초 (두 방식 공통).
- Redis 과목 락 대기: 10초.
- MySQL 세션 `innodb_lock_wait_timeout`: 10초 (Hikari 새 연결 생성 시 설정, 두 방식 공통). 통합 테스트에서만 1초로 줄여 오류를 검증한다.
- MySQL 값은 개별 DB 행 잠금 대기 제한이다. 커넥션 풀 대기, 여러 쿼리, 커밋까지 합친 HTTP 요청 10초 제한이 아니다. Redis 락 대기 제한과 완전히 같은 의미가 아니다.
- DB 잠금 실패/데드락은 `DB_LOCK_FAILURE` / 503, 정원 마감은 `COURSE_FULL` / 409. 자동 재시도하지 않는다. DB 연결 장애는 별도 범주로 기록하고 잠금 실패로 합치지 않는다.
- 로그 `strategy`로 모드를 구분한다. `courseLockWaitMs`는 **Redis 과목 락 대기만** 측정한다. PESSIMISTIC에서 0이라고 DB 대기가 없다는 뜻은 아니다.
- PESSIMISTIC의 `writeMs`는 성공 시 커넥션 확보, DB 락 대기, 저장, 커밋까지 포함한다. 순수 DB 락 대기와 커넥션 풀 대기를 분리한 값이 아니다.

## 최초 부하 비교 계획 (실행 결과는 아래 별도 기록)

같은 JAR / PC / JVM 두 개 / 동일 풀 크기 / 동일 로그 설정 / 데이터 초기화 / 예열 / 실행 순서 교대를 사용한다. 학생500명, 과목1개, 정원100명, 5초 ramp-up, 학생당1요청을 버전별3회 비교한다.

확인: 성공/정원마감/처리불가 코드와 원인, 회차별 전체 및 응답 종류별 P95, 신청 내역과 enrolled_count 일치. 필요하면 DB 잠금 대기와 커넥션 풀 대기를 별도 계측한다. 서로 다른 요청 실패를 모두 정원 마감으로 처리하지 않는다.

기존 결과와 환경이 다르면 개선율을 계산하지 않는다. 이 문서는 비관적 락이 Redis보다 느리거나 빠르다는 결론을 미리 내리지 않는다.


## 2026-10-15 Windows 실행 결과 (사용자 제공)

변경 전 커밋: `74ef96e0d8e5d75030282b02f728d401f427963d`.
각 방식 서버 재시작 후 3회 순차 실행. 모든 PESSIMISTIC 실행 후 REDIS 실행으로, 순서를 교대한 실험은 아님. 첫 회를 사후에 제외하지 않는다. JTL 원본은 사용자 PC에 있으며 아래는 공유된 콘솔/JTL 집계 결과다.

| 방식 | 회차 | 평균 ms | P95 ms | 201 | 409 | 503 |
|---|---|---:|---:|---:|---:|---:|
| PESSIMISTIC | 1 | 10149 | 14117 | 100 | 400 | 0 |
| PESSIMISTIC | 2 | 2132 | 3615 | 100 | 400 | 0 |
| PESSIMISTIC | 3 | 2911 | 5286 | 100 | 400 | 0 |
| REDIS | 1 | 16286 | 20299 | 100 | 344 | 56 |
| REDIS | 2 | 6568 | 10297 | 100 | 381 | 19 |
| REDIS | 3 | 7008 | 10158 | 100 | 361 | 39 |

매회 capacity/enrolled_count/실제 신청 수 모두 100. 503 원인은 아직 응답 본문/서버 로그로 확정하지 않았다. 409도 HTTP 코드만 집계한 결과다.

### 확인한 구조와 아직 확인하지 못한 원인

- 기존 구현은 정원이 이미 찬 경우에도 과목 락을 얻은 뒤에야 정원 마감을 반환한다. 두 방식 공통이다.
- REDIS는 DB 트랜잭션 밖에서 과목 락을 기다린다. PESSIMISTIC은 DB 연결과 트랜잭션을 확보한 뒤 행 잠금을 기다린다. 후자는 다른 DB 작업의 연결 확보에 영향을 줄 가능성이 있지만 이번 실험에는 다른 API 부하/풀 계측이 없어 검증되지 않았다.
- Redisson RLock의 통신 및 Pub/Sub 비용은 원인 후보이지 이 PC에서 병목으로 측정된 사실이 아니다. 공식 설명: https://redisson.pro/docs/data-and-services/locks-and-synchronizers/
- 이 비교에는 로컬 대기열이 없다. 기존 로컬 대기열 최적화 버전과 구분해야 한다.

### 이번 수정

두 전략 모두 학생 검증에서 이미 조회한 과목이 마감됐으면 락 대기 전에 COURSE_FULL을 반환한다. 추가 SELECT는 없다. 사전 확인을 통과한 요청도 최종 쓰기 트랜잭션에서 정원을 재검사한다. 이미 사전 검사를 통과해 대기 중인 요청을 취소하는 기능은 아니다.

기본 `--enrollment.capacity-precheck=true`. 양쪽 모두 false면 기존 대기 흐름을 재현한다. 취소/정원 변경을 추가한다면 읽기 시점에 마감이었다가 직후 자리가 생길 수 있는 거절 정책도 검토해야 한다.

락 타임아웃/Redis 장애/DB 잠금 실패는 샘플링 없이 ENROLLMENT_TIMING에 원인을 남긴다. 검증/저장이 예외로 끝나도 해당 단계 소요시간을 기록한다. Redis 예외가 tryLock 도중 발생하면 해당 lockWaitMs는 완료되지 않은 측정값이므로 0을 실제 무대기로 해석하지 않는다. facadeTotalMs는 락 해제와 HTTP 전체 시간을 포함하지 않는다.

### 재측정 기준

동일 수정 JAR로 두 방식 모두 precheck=true, 같은 JVM/풀/부하 조건으로 비교한다. 타임아웃을 늘려 503을 숨기지 않는다. 예열 1회를 별도 보관하고 데이터 초기화 후 측정 3회, 두 방식에 동일하게 적용한다. 가능한 경우 순서를 교대한다. 응답 코드별 시간과 503 원인을 함께 확인한다. 수정 전후 결과를 같은 표에서 섞어 평균내지 않는다.

성능 개선과 Redis 선택의 정당성은 아직 입증하지 않았다. DB 연결 보호를 선택 근거로 삼으려면 별도 조회 API와 연결 풀 대기를 실제로 측정해야 한다.
