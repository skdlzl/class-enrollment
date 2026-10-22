# 락 진입 전 마감 힌트

## 동작

기본 OFF. enrollment.closed-course-hint.enabled=true일 때 모든 동기 구성(REDIS/PESSIMISTIC/DB_ONLY)에 적용한다. Redis 키 enrollment:closed-hint:v1:{courseId}는 기본 5초 TTL이다.

1. 학생 락과 로컬 대기열 진입 전에 Redis의 마감 표시를 확인한다.
2. 표시가 없거나 Redis 읽기가 실패하면 기존 경로로 진행한다.
3. 표시가 있으면 primary DB에서 enrolled_count >= capacity만 단일 SELECT로 확인한다. 여전히 마감이면 학생 락과 상세 검증을 건너뛰고 COURSE_FULL 409를 반환한다.
4. 빈자리가 있거나 과목이 사라졌으면 표시를 지우고 기존 경로에서 처리한다. DB 확인 자체가 실패해도 힌트로 거절하지 않고 기존 경로로 진행한다.
5. Redis 학생 락을 기다렸던 요청은 획득 후 다시 확인한다.
6. 마지막 좌석 저장은 커밋 후에만 마감 표시를 발행한다. 롤백 시 발행하지 않는다. 기존 처리에서 COURSE_FULL을 받은 경우에도 힌트를 기록한다.
7. 실제 저장은 기존 학생 DB 락, 학점/시간표 검증, 과목 DB 락과 정원 검사를 그대로 수행한다.

## 원안과 차이

플래그만 보고 즉시 409를 반환하면 그 플래그는 거절의 최종 근거가 된다. '힌트이므로 안전하다'고 설명할 수 없다. 커밋 후 mark와 취소 후 clear만으로는 두 작업의 순서가 역전되는 경쟁도 남는다. 이 구현은 positive hit마다 DB를 재확인해 오래된 표시로 빈 과목을 거절하는 문제를 막는다. DB 조회 비용과 연결 확보 대기는 남는다. 조회 시점 뒤 취소로 자리가 생기는 일반적인 동시성은 재신청으로 처리한다.

현재 취소/정원 변경 API는 없다. clear 메서드는 제공하지만 실제 취소 API를 구현했다고 주장하지 않는다. 통합 테스트에서 DB 취소를 원자적으로 모사한 뒤 지연된 mark가 다시 기록되어도 다음 신청이 가능한지 확인한다. 향후 취소 API에서 커밋 후 clear를 호출하면 불필요한 힌트 적중을 줄일 수 있다.

Redis 장애는 힌트 읽기/쓰기 단계에서 best-effort로 처리해 최종 DB 안전장치로 진행한다. Redis 클라이언트 타임아웃을 기다리는 비용 자체는 없어지지 않는다. 혼합/REDIS의 학생 락에 필요한 Redis가 중단된 경우에는 기존 오류 처리가 유지된다.

## 제한과 비교

이미 락이나 DB 연결을 기다리는 요청을 모두 제거하지는 않는다. 정원이 100명이라고 대기 요청이 100건으로 제한되는 것도 아니다. 마감된 과목에 후속 요청이 많을 때 이득을 기대한다. 좌석이 충분한 학생 경합 실험에서는 GET 비용만 추가될 수 있으므로 이번 검증에는 기존 인기 과목 검색 여정을 사용한다.

이 기능은 분산 락 자체의 성능 향상이 아니라 마감 요청을 조기에 거절하는 최적화다. DB_ONLY에도 같은 기능을 적용할 수 있다. 여러 서버가 같은 MySQL을 공유하면 DB 비관적 락도 서버 간 동시성을 제어한다.

## 실행

기존 Java 서버 두 개를 종료하고 업데이트 후 다시 빌드한다. MySQL/Redis는 유지한다.

```powershell
cd C:\portfolio\class-enrollment
git switch feat/redis-ingress-queue
git pull --ff-only
cd backend
.\mvnw.cmd -DskipTests clean package
cd ..
powershell -ExecutionPolicy Bypass -File .\scripts\start-student-contention-servers.ps1 PESSIMISTIC -ClosedCourseHint
```

양쪽 Started 로그 확인 후:

```powershell
node .\scripts\run-enrollment-journey.mjs PESSIMISTIC --search --read-rps 50 --closed-course-hint --warmup
node .\scripts\run-enrollment-journey.mjs PESSIMISTIC --search --read-rps 50 --closed-course-hint
```

동일 JAR에서 OFF 비교는 Java를 종료 후 -ClosedCourseHint 없이 시작하고 실험에서도 --closed-course-hint를 생략한다. 단일 풀 10개, 게이트 OFF, DB admission OFF를 유지한다. 성능 비교에서 조건을 섞지 않는다. DB_ONLY의 ON/OFF 비교도 같은 방식으로 가능하다.

conditions.json은 실제 양 서버 설정을 저장한다. summary.closedCourseHint는 본 측정 구간 카운터 차이다. checks/hits/rejected/stale/marks/redisErrors/dbErrors를 기록한다. rejected가 실제로 발생했는지와 신청 완료율, P95, 조회 실패 및 전송 생략을 함께 평가한다. hit는 DB 1회 확인을 추가하며 marks와 rejected는 서로 다른 지표다. cold miss나 TTL 만료는 기존 안전 경로로 돌아간다.

단위 테스트와 MySQL/Redis 통합 테스트는 동작/정합성을 검증하며 성능 향상을 보장하지 않는다.
