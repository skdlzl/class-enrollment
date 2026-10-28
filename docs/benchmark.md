# 측정과 타임아웃 해석

## 비교 조건

동일 JAR, JVM 2개, JVM당 Hikari 20개, 부하 동시성 128, 회차당 12,000 요청, 외부 API 기본 지연 20ms. 외부 시뮬레이터 listen backlog는 512로 고정하여 기본값 5 때문에 생기는 연결 병목이 락 비교를 왜곡하지 않도록 한다. 과목 50개/각 정원 120과 인기 과목 1개/정원 100을 각각 측정한다. 각 요청은 고유 학생으로 성공 키를 1개 사용한다. 동일 학생 경합/중복 키/만료는 별도 통합 테스트가 검증한다. 총 요청 수와 동시 접속자 수를 혼동하지 않는다.

매 회차 JVM 재시작, 워밍업 100건, DB 재초기화를 수행한다. 세 회차는 전략 실행 순서를 회전한다. 로컬 CLI 기본값은 순차 실행이고, CI는 각 회차를 독립된 동일 사양 runner에서 병렬 실행한다. 한 회차 안에서는 세 전략이 같은 runner/MySQL/Redis를 사용하며 순서 효과를 회차별로 회전한다. 공유 CI의 호스트 편차는 한계로 남는다. 지표는 워밍업 전후가 아니라 **측정 전후의 누적값 차이**로 계산한다. 스크립트의 before/after metric JSON을 함께 보관한다. 타이머의 누적 max/percentile은 단순 차감하면 안 된다. 응답별 정확한 percentile은 CSV를 사용한다.

포화 시 빠른 409를 처리한 것만으로 좋은 성능이라고 판단하지 않는다. 201의 P95/P99·성공 처리량과 503 개수를 함께 본다. 정원 마감 부하는 외부 호출 건수를 줄일 수 있으므로 외부 호출 타이머 COUNT도 비교한다. 이 closed-loop 테스트는 전체 사용자 도착률을 고정한 open-loop 테스트가 아니다. CI 공유 머신 결과는 운영 SLA나 무제한 트래픽 보장이 아니다.

## 오류 코드와 원인

|관측|원인|확인할 증거|
|---|---|---|
|REDIS_LOCK_TIMEOUT|과목 락 입장 예산 소진|외부 시간·정원·같은 과목 요청 집중도|
|EXTERNAL_CONNECT_TIMEOUT|외부 TCP 연결 제한시간 초과|네트워크/서비스 연결 상태|
|EXTERNAL_RESPONSE_TIMEOUT|요청 응답 제한시간 초과|외부 지연 주입값과 external 타이머|
|EXTERNAL_IO_ERROR|연결 거절/리셋 등 I/O 실패|외부 프로세스와 네트워크 로그|
|LEASE_LOST|작업 도중 TTL 만료/소유자 교체|TTL, external/transaction 시간, 장애 테스트|
|REDIS_UNAVAILABLE|Redis 명령 실패 또는 300ms 제한|Redis 상태/지연, 재시작 로그|
|DB_CONNECTION_UNAVAILABLE|풀에서 1초 내 커넥션 미획득 또는 연결 생성 실패|Hikari acquire/timeout, active/pending; 코드만으로 풀 고갈 확정 불가|
|RETRY_EXHAUSTED|8회 충돌/DB 일시 실패|enrollment.retries의 cause 태그, DB deadlock 로그|
|REQUEST_DEADLINE|요청 예산 5초 소진|각 단계 누적 시간|
|DB_TRANSACTION_ERROR|트랜잭션 종료/commit/rollback 실패|DB 로그와 동일 멱등 키 재조회|

5초 예산은 단계 진입/쓰기/commit 직전에서 확인하는 협력적 제한이다. 이미 대기 중인 JDBC/Redis 명령을 실시간 강제 중단하는 hard deadline은 아니다. SQL 대기 1초, 트랜잭션 2초, Redis 명령 300ms, 외부 응답 600ms 제한이 별도로 있다. 서블릿 대기열에 머문 시간도 service timer에 포함되지 않으므로 클라이언트 CSV와 비교해야 한다.

정밀 원인 조사: `SHOW ENGINE INNODB STATUS`, `performance_schema.data_lock_waits`, Hikari pending/active/acquire, 외부 프로세스 상태를 함께 확인한다. 지연 증가를 무조건 Redis 문제나 DB 데드락이라고 결론내리지 않는다. 필요하면 MySQL `innodb_print_all_deadlocks=ON`을 테스트 서버에 설정한다.

## 재현

- 외부 응답 제한: `EXTERNAL_DELAY_MS=1000 docker compose up -d --force-recreate external` → 기본 600ms에서 EXTERNAL_RESPONSE_TIMEOUT.
- 만료: `--enrollment.lease-ms=100`과 외부 지연 200ms, 외부 timeout 600ms → LEASE_LOST, DB 미반영.
- DB 진행 중 만료: 통합 테스트가 학생 행을 별도 커넥션으로 잠그고 TTL이 지나도록 지연시킨 뒤 해제, 신청/인원/멱등 키의 전체 롤백 확인.
- 소유자 교체: 이전 작업의 unlock이 successor 키를 삭제하지 않는 통합 테스트.

채택 문구는 결과 확인 후 작성한다. 예: “외부 검증 20ms, JVM 2개, 12,000건/회 조건에서 Redis 조율 방식이 비관적/낙관적 전략 대비 성공 P95 및 타임아웃에서 유리해 채택했다.” 실제 결과가 이를 뒷받침하지 않으면 수치와 결론을 바꾸어야 한다.

## 대기 시간의 하한과 병목

인기 과목 1개를 직렬 처리하면 외부 응답이 20ms인 것만으로 성공 처리량의 이론적 상한은 50건/초다. DB 작업까지 포함하면 실제 상한은 더 낮다. 정원 100명을 모두 채우려면 외부 대기만 최소 2초가 필요하다. Redis가 이 직렬화 비용을 없애지는 않는다. 대기자가 많거나 외부 응답이 600ms에 가까워지면 입장 예산 5초를 넘는 요청이 나올 수 있다.

50개 과목 분산 부하에서는 동시에 기다릴 외부 호출 수가 늘어난다. DB 트랜잭션 안에서 기다리는 전략은 2 JVM × 커넥션 20개 = 최대 40개 커넥션이 외부 대기에 묶일 수 있다. Redis 전략은 외부 대기 후에 커넥션을 빌리므로 풀 사용 시간 감소가 기대된다. 단, Redis 명령 왕복과 폴링 비용이 추가되므로 **기대 효과와 실제 측정 결과를 구별**한다.

관찰한 timeout을 해석할 때는 클라이언트 15초 제한(HTTP status 0), 서비스 입장/요청 예산, 외부 HTTP 제한, DB 풀/SQL/트랜잭션 제한을 구별한다. `Retry`는 버전 충돌이며 timeout이 아니다. 원본의 VERSION_CONFLICT 재시도 횟수를 시간 초과 건수에 합산하지 않는다.

## 초기 구동 실패와 요청 실패를 분리

실행 [40735140497](https://github.com/skdlzl/class-enrollment/actions/runs/40735140497)에서는 부하 시작 전 첫 JVM이 종료됐다. Flyway/Hikari 초기 연결 로그의 SQLState `08S01`, `Connection attempt exceeded defined timeout`으로 확인한 **DB 초기 handshake 제한**이며, 수강신청 12,000건에서 발생한 timeout으로 집계하지 않는다.

Hikari 풀 획득 제한 1초는 유지하면서 초기화 재시도 예산을 60초로 분리했다. Compose readiness도 UNIX socket ping 대신 애플리케이션 계정의 TCP `SELECT 1` 성공을 요구한다. 임시 초기화 서버가 응답했다는 이유만으로 준비 완료로 판단하지 않는다. 이후 실행에서 재검증한다.
