# 락 선택 비교 실험

## 전환 이유: 가설과 결과를 구분

현재 결과에서 비관적 락은 정합성을 지켰고 Redis보다 빨랐다. 비관적 락의 결함 때문에 전환했다는 이력은 없다. 처음에는 다중 JVM에서 공유되는 락이 필요해 Redisson을 적용했고, 나중에 비관적 락을 대안으로 검증한 순서다. 같은 DB를 공유하면 비관적 락도 여러 서버의 요청을 조정할 수 있다.

검증할 Redis의 장점은 대기를 DB 쓰기 트랜잭션 밖으로 옮긴다는 점이다. 비관적 락은 DB 연결을 확보한 뒤 행 잠금을 기다리므로, 연결 풀이 부족하면 다른 쿼리에 영향을 줄 수 있다. Redis 역시 앞선 학생 검증에는 DB 연결을 사용한다. 이 프로젝트의 Redis 경로는 검증 트랜잭션 종료 후 과목 락을 기다리므로 그 대기 동안 연결을 잡고 있지 않는다.

일반 조회 P95와 연결을 기다리는 스레드 수를 실제로 비교하기 전까지 '조회 장애 방지', 'DB 부하 감소를 입증' 같은 표현을 쓰지 않는다. Redis에는 외부 시스템 운영/장애 대응 비용도 있다. 빠른 신청이 우선이고 DB 연결 부족이 관측되지 않는다면 비관적 락을 선택할 수 있다.

## 변경 범위

- 학생 Redisson 락은 두 방식 모두 유지.
- 선택적 로컬 과목 대기열 → 학생 락 → 새 읽기 트랜잭션에서 학생 검증/사전 정원 확인 → Redis/DB 과목 락 → 최종 정원 확인/저장/커밋.
- 로컬 대기열 기본 OFF. ON일 때 JVM별 같은 과목 요청 하나만 통과. 서버 간 순서는 보장하지 않으며 분산 락을 대체하지 않는다.
- 대기열은 공정 Semaphore, 획득 대기 10초. 초과는 LOCAL_GATE_TIMEOUT/503이며 COURSE_FULL로 바꾸지 않는다. Redis/DB 대기와 합한 전체 HTTP 제한은 아니다.
- 대기/처리 중인 참조가 모두 사라지면 과목별 세마포어를 제거. 예외/인터럽트도 정리. 대기 스레드 자체를 없애는 비동기 큐는 아니다.
- 기존 ENROLLMENT_TIMING은 대기열 통과 이후 시간이며 로컬 대기시간은 LOCAL_GATE_TIMING, 전체 응답시간은 JTL에서 확인.
- `experiment.enabled=true`일 때만 DB를 실제로 읽는 `/api/experiment/read`와 Hikari 풀 상태 `/api/experiment/pool` 제공. 실험용 로컬 환경에서만 켠다.

## 실행

두 기존 서버를 종료하고 최신 코드로 빌드한다. 두 서버 모두 같은 전략과 gate 설정을 사용한다.

```powershell
cd C:\portfolio\class-enrollment\backend
.\mvnw.cmd -DskipTests package
java -jar .\target\class-enrollment-backend-0.0.1-SNAPSHOT.jar --server.port=8080 --enrollment.course-lock-strategy=REDIS --enrollment.capacity-precheck=true --enrollment.local-gate-enabled=false --experiment.enabled=true --logging.file.name=logs/experiment-8080.log
```

다른 터미널에서 같은 JAR을 `--server.port=8081`, `--logging.file.name=logs/experiment-8081.log`로 실행한다. 비관적 락은 두 서버 모두 `PESSIMISTIC`, gate 단계는 두 서버 모두 `--enrollment.local-gate-enabled=true`로 바꾼다. 풀 크기/HTTP 스레드/타임아웃은 두 모드 동일하게 유지한다.

프로젝트 루트의 별도 PowerShell:

```powershell
# 예열, 매 실행마다 테스트 데이터 초기화됨
.\scripts\run-lock-comparison.ps1 -Strategy REDIS -Warmup
# 본 측정 3회, 서버 재시작하지 않음
1..3 | ForEach-Object { .\scripts\run-lock-comparison.ps1 -Strategy REDIS }
```

이 스크립트는 기존 JMX/CSV를 덮어쓰지 않는다. 별도 `lock-comparison.jmx`와 매회 새 CSV를 사용한다. 기존 수동 JMX와 실행기가 다르므로 기존 결과와 합쳐 평균내지 않고 두 모드를 새 실행기로 비교한다. 새 실험을 위한 재빌드/서버 재시작이 필요하므로 기존 수정본의 측정 1회는 별도 보관한다.

| 단계 | gate | Users | Capacity | Mixed |
|---|---|---:|---:|---|
| 기준 | false | 500 | 100 | 없음 |
| 로컬 대기열 | true | 500 | 100 | 없음 |
| 부하 증가 | true | 1000, 2000 | 100 | 없음 |
| 실제 저장 | true | 500, 1000, 2000 | Users와 동일 | 없음 |
| 조회 영향 | false와 true 각각 | 500, 1000, 2000 | 100 | 있음 |

각 행/조건을 REDIS와 PESSIMISTIC에서 모두 실행한다. Users/Capacity/Ramp/Gate/Mixed가 같아야 짝이다. 조건별 예열1회, 본3회. 가능한 경우 모드 실행 순서를 교대한다. 차이가 충분히 확인되거나 실패가 많아지면 큰 부하 추가는 중단하고 원인 확인.

```powershell
# 서버도 gate=true로 실행한 뒤 사용
.\scripts\run-lock-comparison.ps1 -Strategy REDIS -Gate $true -Users 1000 -Capacity 100 -Warmup
# 저장 성능 (정원 여유)
.\scripts\run-lock-comparison.ps1 -Strategy REDIS -Gate $true -Users 1000 -Capacity 1000
# 조회 동시 부하 (비교 대상도 같은 값 사용)
.\scripts\run-lock-comparison.ps1 -Strategy PESSIMISTIC -Gate $true -Users 500 -Mixed
```

## 측정값 해석

- 등록 요청은 Ramp초 동안 Users개, 학생별1회, 두 서버 균등 분배. 기본 Ramp5초.
- 일반 조회는 Mixed에서 서버별 클라이언트1개가 100ms 간격으로 반복(응답 대기 포함 closed-loop). 고정 RPS 부하가 아니다. 느려지면 실제 요청수도 줄어드므로 성공 건수와 P95를 함께 본다.
- 풀 상태는 양쪽 모두 500ms 간격으로 샘플링. active/idle/waiting/total은 연결 수와 대기 스레드 수이며 DB 잠금 대기시간 그 자체가 아니다. 짧은 피크를 놓칠 수 있다.
- 탐침 기본30초. 신청이 더 오래 걸리면 경고하며 두 방식 모두 ProbeSeconds를 똑같이 늘려 재실행한다. 느린 모드만 측정 시간을 늘려 비교하지 않는다.
- JMeter summary에는 조회/풀 샘플도 포함된다. 성능 비교는 별도 `summary.csv`의 enrollment와 read 응답 코드별 집계를 사용한다. 조회/풀 결과는 신청 시작~마지막 완료 구간에 시작한 샘플로 제한한다.
- 결과 폴더: conditions.json(설정/코드), samples.jtl(원본), summary.csv(종류/코드별 평균/P95), pool-overlap.csv, database.txt. 원본에는 예열 결과도 남긴다.
- DB 인원 두 값 일치와 성공201 건수 일치를 확인한다. 409는 응답 코드만으로 모든 원인을 단정하지 않고 필요한 경우 서버 로그 확인.
- 원인별 503 로그, 성공/거절 각각의 응답시간, 일반 조회 처리 건수/실패/P95, 풀 waiting 관측값을 함께 비교한다.

## 검증 범위

CI는 MySQL/Redis Testcontainers로 두 전략의 로컬 대기열 정합성, 학점 제한, 롤백, 실제 DB 락 타임아웃 뒤 재요청 성공, 실험 조회/풀 API를 검사한다. 세마포어 동시 접근/타임아웃/인터럽트/예외 해제도 별도 검사한다. Windows PowerShell 실행기와 JMeter 부하 결과는 사용자 PC에서 실행해야 하며 CI 성공을 성능 검증으로 간주하지 않는다.
