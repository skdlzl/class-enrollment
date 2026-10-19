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
