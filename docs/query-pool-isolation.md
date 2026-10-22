# 과목 조회 연결 풀 분리

## 변경 범위

enrollment.query-pool.enabled=true일 때 동일 MySQL에 두 Hikari 풀을 만듭니다. spring.datasource.hikari.maximum-pool-size는 두 풀의 총 연결 예산입니다. 총 10개, enrollment.query-pool.size=4라면 조회 4개와 신청 및 기타 작업 6개입니다. 각 풀은 별도 대기열을 가지지만 DB 서버의 CPU, 디스크, 버퍼는 공유합니다.

GET /api/courses만 catalogJdbcTemplate을 사용합니다. 학점·시간표·정원 검증과 신청 저장, 신청 내역 조회, Flyway 및 기타 기존 JdbcTemplate/JPA 작업은 primary 신청 풀을 유지합니다. 신청 트랜잭션을 두 풀로 나누지 않습니다. 조회 결과를 정원 예약으로 취급하지 않으며 최종 DB 락과 검증은 유지합니다. 조회 풀은 같은 primary DB를 읽으며 복제본이나 캐시가 아닙니다.

기본값은 비활성입니다. 비활성일 때 기존 Spring Boot 단일 풀 자동 설정을 사용합니다. 활성화 시 spring.datasource.url/username/password 및 hikari 설정을 바탕으로 두 풀을 생성한 뒤 최대 연결 수와 minimumIdle을 각 예산으로 맞춥니다. 1 <= 조회 연결 수 < 총 연결 수 조건을 만족하지 않으면 시작에 실패합니다.

## 비교 조건

- 혼합 구성(PESSIMISTIC), 서버당 총 연결 10개
- 조회 풀 4개, 신청 및 기타 작업 풀 6개
- db-admission.limit=0, local-gate-enabled=false, queue.enabled=false
- 사용자 500명/5초, 실행별 검색 과목 1만 개, 배경 조회 서버당 50RPS
- 예열 제외, 기존 조건 유지

서버에 --enrollment.query-pool.enabled=true --enrollment.query-pool.size=4를 추가합니다.

```powershell
node .\scripts\run-enrollment-journey.mjs PESSIMISTIC --search --read-rps 50 --db-concurrency 0 --query-pool-size 4 --warmup
node .\scripts\run-enrollment-journey.mjs PESSIMISTIC --search --read-rps 50 --db-concurrency 0 --query-pool-size 4
```

실험기는 양쪽 서버의 조회 풀 설정과 총 예산을 검사합니다. conditions.json에 설정과 풀 구성을 저장합니다. summary.poolDetails에는 풀별 최대 활성 수와 최대 대기 수가 기록됩니다. phaseTimings의 pool.write.acquire/usage와 pool.catalog.acquire/usage로 실제 점유 및 대기를 구분합니다. pool 최상위 값은 두 풀의 합계이므로 max=10을 유지합니다.

## 검증과 한계

실제 MySQL 통합 테스트에서 신청 풀 6개 연결을 모두 잡은 채 과목 검색이 조회 풀로 완료되는지 검증합니다. 별도 테스트로 혼합 신청이 커밋되고 검색에 갱신된 인원수가 보이는지 확인합니다. 기존 장애 안전성 테스트도 함께 실행합니다.

성능 수치는 아직 측정하지 않았습니다. 조회용 연결을 확보하는 대신 신청 작업이 사용할 연결 수는 줄어듭니다. 조회 P95뿐 아니라 신청 완료율, 완료 시간, 타임아웃, 전송 생략을 함께 평가합니다. 전체 스레드/CPU/디스크 과부하까지 격리하지는 않습니다. 순수 DB에도 동일 분리를 적용할 수 있으며 효과를 Redis 자체의 성능으로 주장하지 않습니다.
