# 큰 부하에서 Redis 접수 구조와 기존 동기 경로 비교

Redis를 사용하는 방향은 유지합니다. 이 실험은 어느 부하까지 접수·조회·최종 처리가 유지되는지,
이전 동기 경로와 비교할 때 어떤 차이가 있는지 확인합니다. 특정 방식의 승리를 전제하지 않습니다.

## 고정 조건

- 같은 JAR, 같은 PC, 애플리케이션 8080/8081, 같은 MySQL 및 Redis(6380).
- 신청 1,000 → 5,000 → 10,000건을 각각 5초에 분산 전송. '동시에 도착'으로 표현하지 않습니다.
- 매 회차 새 학생과 과목을 생성하며 정원100. 기존 데이터 삭제 없음.
- 조회는 서버당50RPS, 전체100RPS. 각 baseline/mixed는90초.
- 신청 관찰 제한60초. queued의 접수/조회 HTTP 제한10초, 동기 신청 HTTP 제한60초.
- queued의 최종 결과에는2초 폴링 대기가 포함됩니다. 동기는 HTTP 최종응답까지 측정합니다.
- Hikari 풀은 양쪽 서버10으로 명시해 실행합니다. HTTP 스레드/큐 등 다른 설정은 동일하게 유지합니다.
- 기본 비교 대상은 기존 비관적 과목 락+로컬 대기열 켜짐입니다. 로컬 대기열 꺼짐도 별도 모드로 측정 가능합니다.
- 기존 동기 경로에는 학생 Redisson 락이 남아 있습니다. 'Redis를 전혀 쓰지 않는 MySQL 방식' 비교가 아닙니다.
- Redis 접수와 동기는 API 의미, 내부 대기시간 제한, DB 처리 방식이 다릅니다. 락 알고리즘만 비교하는 실험이 아닙니다.

## 첫 번째: Redis 접수 구조

두 서버를 종료하고 최신 JAR을 빌드한 뒤 각각 실행합니다.

```powershell
java -jar .\target\class-enrollment-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=queue --server.port=8080 --experiment.enabled=true --spring.datasource.hikari.maximum-pool-size=10
```

두 번째 창은 같은 명령의 포트를8081로 바꿉니다.

```powershell
cd C:\portfolio\class-enrollment
node .\scripts\run-enrollment-stress.mjs --mode queue
```

각 단계 약3분, 전부 진행되면 약9분+데이터 생성 시간입니다.
특정 단계만 실행하려면 `--stage 1000` 또는 `--stage 5000`, `--stage 10000`을 추가합니다.

## 두 번째: 기존 비관적 과목 락 경로

Redis의 미완료 요청을 먼저 처리한 뒤 두 서버를 종료합니다. 같은 JAR을 다음 설정으로 실행합니다.

```powershell
java -jar .\target\class-enrollment-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=queue --server.port=8080 --experiment.enabled=true --spring.datasource.hikari.maximum-pool-size=10 --enrollment.queue.enabled=false --enrollment.course-lock-strategy=PESSIMISTIC --enrollment.local-gate-enabled=true --enrollment.capacity-precheck=true
```

두 번째 창은8081입니다. queue 프로필은 동일한 Redis6380 주소를 사용하기 위한 것이며, queue.enabled=false로 비동기 워커는 실행하지 않습니다.

```powershell
node .\scripts\run-enrollment-stress.mjs --mode pessimistic
```

로컬 대기열 없는 동기 경로는 두 서버의 local-gate-enabled=false와 스크립트 `--mode pessimistic-ungated`를 짝지어 사용합니다.

## 결과 판단 및 중단

- 조회 전체 P95 외에 신청이 몰린5초의 조회 P95도 따로 출력합니다. 유휴 구간이 결과를 희석하는 것을 구분합니다.
- 접수202의 속도와 최종확정/거절 시간을 혼동하지 않습니다.
- 전체 응답코드 분포, 실패, 미완료, 부하 생성 누락, 도착 스케줄 지연을 저장합니다.
- 응답 유실/타임아웃은 서버에서 처리됐을 수 있으므로 SUBMIT_UNKNOWN으로 남깁니다.
- Redis 큐의 대기 상한5,000은 유지합니다. 초과 시429가 나올 수 있으며 정상 수용 처리로 계산하지 않습니다.
- 1초 이상 밀린 신청 전송은 CLIENT_SKIPPED로 기록합니다. 조회 동시미완료100건 상한에 도달하면 조회 생성도 누락될 수 있습니다.
- 생성 누락/네트워크 오류가 많으면 해당 실행을 서버 처리속도 우위 증거로 사용하지 않습니다.
- 미완료/실패/생성 누락/조회 실패 발생 시 결과를 저장하고 다음 자동 증량을 중단합니다.
- DB 최종 정합성 검사는 모드별로 다릅니다. queued는 DB결과 기록과 신청 수까지, 동기는 신청 수와 인원 카운터를 비교합니다.
- pool 표는1초 단위 순간 표본의 최대 active/waiting입니다. 실제 최대 대기 전체를 포착한다고 보장하지 않습니다.
- conditions.json에 실제 서버 설정을 기록합니다. 양쪽 서버의 모드·풀 설정이 다르면 실험을 시작하지 않습니다.

부하 생성기와 서버가 같은 PC라면 자원을 서로 공유합니다. 이번 로컬 결과를 실서비스의 최대 처리량으로 일반화하지 않습니다.

## 시간 초과 진단

최신 JAR을 experiment.enabled=true로 실행하면 /api/experiment/diagnostics가 누적 카운터를 제공합니다.
각 phase 전후 스냅샷과 Node 생성기 정보를 baseline.json / mixed.json에 저장하며,
혼합 구간 진단은 summary.json에도 포함합니다. 재시작 없이 스냅샷 차이로 비교합니다.

- entered/completed/errors: 서블릿 필터 진입/종료/오류 수. HTTP 진입 전 연결 대기는 포함하지 않습니다.
- httpTotalMs: 필터 진입부터 종료까지의 누적 시간. 응답이 클라이언트에 전달됐다는 보장은 아닙니다.
- redisCalls/redisTotalMs: 접수 store 호출 횟수/누적 시간. 직렬화, Redisson 연결 대기와 통신을 포함하며 Redis 명령 실행 시간만 뜻하지 않습니다.
- generator.eventLoopP95Ms/eventLoopMaxMs: Node 이벤트 루프 지연. 20ms 해상도의 측정 기준값이 포함됩니다.
- generator.cpuMs/wallMs/rssBytes: 해당 Node 프로세스의 CPU 사용 시간, 관찰 시간, 종료 시점 메모리. 서버나 Docker CPU가 아닙니다.

카운터 스냅샷은 원자적 전체 스냅샷이 아니므로 처리 중에는 작은 시점 차이가 있습니다.
HTTP 종료 시에 Redis 시간을 집계하므로 진행 중 요청은 아직 시간 합계에 포함되지 않습니다.
진단 API도 실패하면 error/status를 남기며 0으로 대체하지 않습니다.
타임아웃과 큐 크기, 디스크 동기화 정책은 변경하지 않았습니다.
