# 조회부터 신청 완료까지 비교

과목 락만 REDIS/PESSIMISTIC으로 바꾼다. 학생 Redisson 락은 공통이다. 큐와 로컬 대기열은 OFF, 사전 정원 검사는 ON, 서버당 DB 연결 풀은 10이다.

500명이 5초 동안 시작한다. 새 과목 5개(각 정원 100명)와 학생 500명을 매 실행 생성한다. 기존 데이터는 삭제하지 않는다. 실제 GET /api/courses 결과에서 잔여 정원이 있는 첫 과목을 선택한다. COURSE_FULL이면 다시 조회하여 미시도 과목을 학생 순번에 따라 선택한다. 최대 신청 3회, 사용자당 전체 제한 60초, 성공 시 즉시 종료한다. 조회 결과는 예약이 아니며 최종 정원 검사는 기존 서버 로직에서 한다.

총 정원이 500이어도 최대 재시도 횟수 때문에 모두 성공한다는 보장은 없다. 이 시나리오는 인기 과목 우선, 대안 과목 허용, 생각하는 시간 없음의 합성 사용자 모형이다. 실제 사용자 행동을 재현했다고 단정하지 않는다. 별도 CS 목록 조회는 서버당 10 RPS로 baseline/mixed 각각 90초 유지한다. 사용자 흐름에서 발생한 목록 조회는 이 배경 조회에 추가된다.

## 실행

기존 서버를 종료한 뒤 아래 명령에서 STRATEGY를 두 서버 모두 REDIS 또는 PESSIMISTIC으로 동일하게 바꾼다.

```powershell
cd C:\portfolio\class-enrollment
 git switch feat/redis-ingress-queue
 git pull --ff-only
```

8080 창:
```powershell
cd C:\portfolio\class-enrollment\backend
java -jar .\target\class-enrollment-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=queue --server.port=8080 --experiment.enabled=true --spring.datasource.hikari.maximum-pool-size=10 --enrollment.queue.enabled=false --enrollment.course-lock-strategy=REDIS --enrollment.local-gate-enabled=false --enrollment.capacity-precheck=true
```
8081 창: 위 명령의 server.port만 8081로 변경한다. queue 프로필은 Redis 접속 설정이며 queue.enabled=false로 작업자는 꺼져 있다. 이번 변경은 스크립트만이므로 기존 최신 JAR 재빌드는 필요 없다.

두 방식 모두 동일하게 전체 예열 1회 후 본 측정 3회를 실행한다. 쿼리만 예열하는 기존 스크립트와 달리 --warmup도 전체 신청 흐름을 실행하고 별도 결과를 보존한다. 자동으로 느린 회차를 빼지 않는다.

```powershell
cd C:\portfolio\class-enrollment
node .\scripts\run-enrollment-journey.mjs REDIS --warmup
```
예열 오류나 미확인 요청이 없을 때:
```powershell
1..3 | ForEach-Object {
    node .\scripts\run-enrollment-journey.mjs REDIS
    if ($LASTEXITCODE -ne 0) { throw '결과를 보존했습니다. 오류를 확인한 뒤 진행하세요.' }
}
```
이후 두 서버를 PESSIMISTIC으로 재시작하고 위 두 실험 명령의 REDIS를 PESSIMISTIC으로 바꾼다. 환경 변동 확인이 필요할 때만 실행 순서를 바꿔 검증한다.

## 해석

- 핵심: 최종 성공률과 첫 조회부터 성공 응답까지의 P95. 성공자 P95만으로 평가하지 않는다.
- 실패, 대안 없음, 재시도 한도, 응답 불명은 별도 집계한다. 503/네트워크 오류는 다른 과목으로 재시도하지 않는다.
- 사용자 조회, 신청별 지연, 재선택 사용자 수, 별도 배경 조회, DB 정합성, 연결 풀 샘플, 클라이언트 HTTP 연결 대기를 함께 보존한다.
- 성공 완료 시간에는 클라이언트 HTTP 연결 대기도 포함된다. 서버 도착이 동시에 이루어졌다는 의미가 아니다.
- DB 확인은 정원/카운터/실제 신청 수 및 학생의 복수 신청 여부를 확인한다. 응답 불명으로 클라이언트 성공 수와 DB 성공 수가 다르면 정상 비교로 취급하지 않는다.
- 폴더는 jmeter/results/journey-전략-warmup또는measured-시간-ID 이다. summary.json과 mixed.json을 비교한다.
- 이전 단일 과목 실험과는 시나리오가 달라 성능 개선율을 직접 계산하지 않는다. Redis가 더 유리하다는 결론은 사전 가정하지 않는다.

## v2 측정기 수정

measurementVersion=journey-v2-isolated-client. 사용자 흐름은 별도 HTTP Agent를 사용한다. 서버별 사용자 250명에 맞춰 사용자 조회/신청 각각 maxSockets=250으로 설정한다. 배경 조회 Agent는 기존 16개를 유지하며 사용자 조회와 공유하지 않는다. 동일 Node 프로세스와 PC 자원은 여전히 공유하므로 완전한 부하 생성기 격리는 아니다.

서버 락/DB 풀/타임아웃 설정은 변경하지 않는다. 클라이언트 연결 제한이 바뀌어 실제 서버 유입량이 달라질 수 있으므로 v1 결과와 개선율을 계산하지 말고 두 전략 모두 v2로 측정한다. socketAssignedMs는 연결 배정까지의 클라이언트 대기다. afterSocketMs는 각 요청의 전체 시간에서 연결 배정 시간을 뺀 값으로, TCP 연결/네트워크/서버 처리 등이 포함되며 순수 서버 처리 시간이 아니다. P95끼리 빼지 않고 요청별로 계산한다.

failureGroups에 HTTP 상태/오류 코드/메시지를 보존한다. LOCK_ACQUISITION_TIMEOUT의 학생/과목 구분은 메시지 및 서버 ENROLLMENT_TIMING의 outcome을 교차 확인한다. 이전 v1 원시 결과에는 메시지가 없으므로 새 측정으로 이전 실패 위치를 소급 확정할 수 없다.

## v3 검색 부하 (--search)

새 API는 grade(1~4)와 keyword(과목명 부분 일치)를 지원한다. 50행씩 커서 페이지 조회하며 정원과 현재 신청 인원을 함께 반환한다. V6 마이그레이션으로 target_grade 및 학과/학년 조회 인덱스를 추가한다. LIKE 와일드카드는 문자 그대로 이스케이프하며 SQL은 바인딩한다. 기존 API 호출은 유지된다.

매 실행 새 검색 데이터 10,000개를 만든다(10개 학과, 1~4학년). 기존 데이터는 보존하므로 전체 DB 과목 수는 10,000보다 많다. 사용자 500명은 같은 학과/학년의 목록 2페이지(각 50개)를 보고 '수강실습' 과목명으로 검색한 뒤 결과에서 신청한다. 신청 대상은 5개 과목(각 정원 100)으로 유지한다. 모든 1만 과목에 신청을 분산한 실험은 아니다. 마감 후 재선택 때도 조회 3회를 반복한다. 사용자 생각 시간은 없으므로 실사용 행동의 정확한 재현으로 해석하지 않는다.

배경 조회 기본값은 서버당 50 RPS(총 100), 학과/학년/과목명을 순환하고 50행씩 가져온다. --read-rps 100처럼 조절 가능(1..200)하지만 비교하는 두 전략에 같은 값을 쓴다. 이전 10RPS 단순 조회 실험과 직접 개선율을 계산하지 않는다. 사용자 HTTP 풀과 배경 HTTP 풀은 분리하고 연결 대기 및 skipped를 보존한다. 배경 풀은 서버당 16 연결이며 대기가 늘면 서버 시간과 구분해서 해석한다. 결과가 불확실하면 자동 증량하지 않는다.

이번 변경은 백엔드 재빌드 필요. 두 서버를 종료한 뒤:
```powershell
cd C:\portfolio\class-enrollment
git pull --ff-only
cd backend
.\mvnw.cmd "-Dtest=CourseQueryControllerTest" test
if ($LASTEXITCODE -eq 0) { .\mvnw.cmd -DskipTests package }
```
이후 위 서버 명령으로 두 서버를 같은 전략으로 실행한다. 첫 서버 시작 시 Flyway V6 완료 후 두 번째 서버를 시작한다.
```powershell
cd C:\portfolio\class-enrollment
node .\scripts\run-enrollment-journey.mjs PESSIMISTIC --search --read-rps 50 --warmup
```
예열 결과 확인 후 --warmup을 빼고 3회 측정한다. REDIS도 동일한 설정으로 예열 1회와 본 측정 3회를 기록한다. 본 측정의 503도 버리지 않는다. 검색 데이터 생성은 측정 이전이며 생성 SQL은 fixture.sql에 모두 저장한다.

검증: Node 테스트 19개 통과. 백엔드 테스트 추가했으나 작업 환경의 Maven Central DNS 접근 실패로 실행 불가, 사용자 환경의 위 테스트 명령으로 확인 필요.

## 로컬 대기열 최적화 비교 (--gate)

기존 LocalCourseGate를 활성화하는 옵션이며 새 비동기 큐가 아니다. 각 JVM은 같은 과목 요청 하나만 학생 검증 및 과목 락 단계로 통과시킨다. 대기 뒤 새 트랜잭션에서 정원을 검사하므로 마감된 요청은 Redis 과목 락을 획득하기 전에 COURSE_FULL로 종료될 수 있다. 최종 저장은 기존 Redis/DB 과목 락 안에서 정원을 다시 확인한다. 이는 Redis 구현 최적화의 가설이며 아직 새 부하 결과는 없다.

서버에 --enrollment.local-gate-enabled=true --enrollment.local-gate-wait-ms=10000 을 추가하고 실행 스크립트에 --gate를 추가한다. gate와 precheck 설정이 양 서버에서 맞지 않으면 중단한다. Redis 락 대기 제한 10초는 변경하지 않는다. 로컬 대기는 별도 단계이므로 전체 요청 제한이 10초라는 뜻은 아니다. 전체 사용자 흐름 제한은 기존 60초이다. LOCAL_GATE_TIMEOUT도 실패로 포함한다.

```powershell
node .\scripts\run-enrollment-journey.mjs REDIS --search --read-rps 50 --gate --warmup
```

폴더명 gate-on과 conditions.json의 gate=true로 기존 gate-off 실험과 구분한다. 503 감소, 최종 성공률, 조회→성공 시간, 검색 지연, skipped를 함께 확인한다. 409도 실제 DB 정원 검증에 따른 응답이어야 하며 오류를 임의로 409로 변환하지 않는다. MySQL에도 --gate를 지원하므로 Redis gate-on과 MySQL gate-off 비교를 순수 락 성능 비교로 표현하지 않는다. 백엔드 코드는 변경하지 않았으므로 JAR 재빌드는 필요 없다.

## Gate 내부 마감 요청 단축

로컬 대기열을 통과한 직후, 학생 Redis 락 획득 전에 별도 읽기 전용 트랜잭션에서 과목 정원 마감 여부만 조회한다. 마감이면 COURSE_FULL로 종료하며 try-with-resources로 permit을 반환한다. 캐시하지 않으므로 취소로 재개방된 정원을 영구 차단하지 않는다. 자리가 있다는 조회는 예약이 아니며 기존 학생 검증, 과목 락, 최종 정원 검증, 커밋 후 해제를 그대로 수행한다. gate OFF 경로는 변경하지 않는다. 양 과목 락 전략에 동일하게 적용된다.

한계: 마감 전에는 DB 조회가 하나 추가된다. 존재하지 않는 학생/잘못된 신청이라도 이미 마감된 과목이면 COURSE_FULL 응답이 우선될 수 있다. 마감 후 검증 비용 축소이며, 마감 전 쓰기 처리량이나 전체 타임아웃 해소를 보장하지 않는다. 실제 결과는 재측정 필요하다. 백엔드 재빌드 필요.

검증 테스트 GatedCapacityPrecheckTest: 마감 시 Redis/학생 검증 생략, 예외 후 permit 재사용, 사전 검사 통과 후 최종 검사에서 마감되는 경우 락 해제, precheck OFF 동작. 로컬 환경은 Maven 의존성 부재로 Java 테스트 실행 불가; 사용자 환경에서 테스트 후 빌드한다.
