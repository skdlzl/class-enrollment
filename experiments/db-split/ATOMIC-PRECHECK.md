# DB 정원 보호와 Redis 마감 선차단

기존 비교 모드는 유지합니다. 새 옵션은 A 또는 B 모드에서 외부 API 없이 사용합니다.
서비스 응답은 접수가 아니라 DB 커밋 후 확정 결과입니다. 큐나 캐시는 신청의 정합성 근거가 아닙니다.

## 처리 순서

Redis 마감 힌트 확인 → 힌트가 있으면 기존 요청 여부와 DB 현재 정원 재확인 →
학생 DB 행 잠금 → 동일 요청 ID 조회 → 학점/시간표 검증 →
정원 미만 조건부 UPDATE → 신청 저장과 학점 증가 → 커밋 → 마감 힌트 갱신.

조건부 UPDATE도 DB 행 잠금을 사용하며 커밋까지 유지합니다.
이는 무잠금 처리가 아닙니다. 성공한 정원 증가도 신청 저장 실패 시 함께 롤백됩니다.
유니크 제약은 중복 저장을 막습니다. 학점/시간표는 학생 잠금으로 보호합니다.

## 마감 힌트의 한계와 복구

- 키는 실행 ID/학기/과목으로 분리하고 TTL은 5초입니다.
- 취소 커밋 후 힌트를 지웁니다. 취소 실패 시 지우지 않습니다.
- 취소와 힌트 쓰기가 엇갈리거나 무효화가 실패해도 DB 현재 정원을 재확인합니다.
- 기존 요청 ID는 정상 처리 경로로 보내 멱등 결과와 요청 ID 충돌 검사를 유지합니다.
- Redis 런타임 오류는 DB 경로로 우회합니다. Redis 접속 타임아웃만큼 지연될 수 있습니다.
- Redis가 처음부터 내려가 있으면 현재 Redisson 빈 생성이 실패할 수 있습니다.
  이때 ClosedPrecheck 없이 기동해야 합니다. 자동 기동 폴백을 구현했다고 주장하지 않습니다.
- 마감 힌트 갱신 실패는 이미 커밋된 신청 결과를 실패로 바꾸지 않습니다.
- 힌트 적중 때도 DB 조회가 있습니다. DB 연결 없이 즉시 거절하는 구조가 아닙니다.
- 아직 접수되지 않은 요청만 줄입니다. 이미 경합 중인 요청은 없애지 않습니다.
- 새 요청에서는 다른 거절 사유보다 COURSE_FULL이 먼저 반환될 수 있습니다.
- 취소된 신청을 새 ID로 다시 신청하는 것은 기존 정책대로 중복으로 거절합니다.

## 비교 실행

먼저 커밋된 소스로 scripts/build.ps1을 실행합니다. 각 구성에서 예열 후 본 측정합니다.
같은 데이터와 부하를 사용합니다. 두 서버에 동일 옵션이 적용됐는지 스크립트가 검증합니다.

기준선: start-comparison.ps1 -Mode B -AtomicCapacity
개선안: start-comparison.ps1 -Mode B -AtomicCapacity -ClosedPrecheck

각 기동 후:
run-comparison.ps1 -Jmeter <jmeter.bat 경로> -Warmup
run-comparison.ps1 -Jmeter <jmeter.bat 경로>
stop-comparison.ps1

조건부 UPDATE 효과와 Redis 효과를 혼동하지 않도록 양쪽에 AtomicCapacity를 켭니다.
conditions.json과 context.json에 옵션이 기록됩니다.
조회 P95와 신청 완료 P95 및 완료율을 함께 비교합니다.
closedPrecheck.rejected, redisFailure, refreshFailure 지표와 capacityUpdate.sql 실행 횟수를 확인합니다.
마감 전 몰린 요청에는 개선이 작을 수 있으며 성능 향상은 아직 측정하지 않았습니다.

## 검증

bash backend/mvnw -B -f experiments/db-split/backend/pom.xml -Dtest=ClosedCoursePrecheckTest,AtomicCapacitySafetyTest,EnrollmentFacadeTest,SetNxFacadeTest test

AtomicCapacitySafetyTest는 Docker MySQL로 기존 정원/학점/시간표 동시성,
중복 요청, 롤백, 취소 테스트를 조건부 UPDATE 경로에서 재사용합니다.
마감 힌트 테스트는 Redis와 JDBC 모의 객체를 사용합니다.
실제 Redis 장애 및 두 서버 혼합 부하 측정은 별도로 수행해야 합니다.
