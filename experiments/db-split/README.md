# DB 분리와 락 방식 비교

이 디렉터리는 기존 수강신청 앱과 분리된 실험 모듈입니다. `feat/reduce-course-lock-scope`의 `407ddbf`에서 시작했으며 과거 큐·캐시 구현과 결과는 그대로 보존합니다. 프론트는 사용하지 않습니다.

**현재 구현 범위는 A 기준선입니다. B와 C는 아직 구현하지 않았습니다.** A의 안전성과 계측을 먼저 확인한 뒤 진행합니다. 기존 JPA 앱의 성능 수치를 새 JDBC 실험 모듈의 기준선으로 재사용하지 않습니다.

| 단계 | 배치 | 동시성 제어 | 질문 |
|---|---|---|---|
| A, 현재 | 단일 MySQL | 학생 DB 락 → 검증 → 과목 DB 락 → 저장 | 공통 로직과 계측이 정상인가? |
| B, 후속 | 학사 A / 수강신청 B | A 단계와 같은 신청 트랜잭션 | 분리가 학사 조회를 보호하는가? |
| C, 후속 | B와 같은 배치 | DB 안전장치 밖에 학생·과목 Redis 락 추가 | DB 연결 밖의 대기가 신청용 조회를 보호하는가? |

C는 검증을 포함한 과목 Redis 락 점유 구간을 별도로 측정합니다. A/B의 과목 DB 락보다 구간이 넓어질 수 있으므로 결과를 순수한 락 알고리즘 차이로 해석하지 않습니다. Redis를 기다리는 동안 DB 연결을 먼저 잡지 않도록 구현할 예정입니다.

## 공통 데이터와 안전장치

- 학사 영역: `academic_student`, `subject`
- 신청 영역: `student_term`, `offered_course`, `offering_schedule`, `enrollment`
- 학생 자격과 최대 학점은 신청 기간 전에 확정한 정보입니다. `enrolled_credits`는 신청 DB가 직접 관리합니다.
- 학생 행 잠금 → 학점·시간표 검증 → 과목 행 잠금 → 신청 내역과 집계 갱신 → 커밋 순서입니다.
- 학생 락은 다른 학생의 요청을 막지 않습니다. 같은 학생이 서로 다른 과목에 동시에 신청하는 테스트에서 필요성을 검증합니다.
- 중복 요청 ID는 동일한 결과를 반환합니다. 취소도 학생 → 과목 순서로 처리하고 내역과 집계를 함께 변경합니다.
- 수강 기간 중 학점·시간표·자격 정보 변경은 이번 실험 범위에서 제외합니다. 취소 후 같은 과목에 새 요청 ID로 재신청하는 기능도 제외합니다. 취소 API는 집계 복원 검증용입니다.
- DB 예외와 응답 유실은 성공 여부 불명으로 보존합니다. 요청 ID로 재조회·재시도할 수 있으며 404 한 번만으로 미저장을 단정하지 않습니다.
- DB 롤백과 재시도 테스트를 서버 강제 종료 테스트라고 부르지 않습니다. 프로세스 종료와 Redis 장애 주입은 별도 후속 검증입니다.

## A 실행 — Windows PowerShell

필요한 도구는 Docker Desktop, Java 17, Node.js, JMeter **5.6.3**입니다. 기존 서버와 DB는 변경하지 않습니다. 실험 MySQL은 `53316`, 앱은 `28080`, `28081`을 사용합니다. 기존 실험과 동시에 부하를 실행하지 마세요.

```powershell
cd C:\portfolio\class-enrollment
git fetch origin
git switch experiment/db-split
git pull --ff-only

# Docker Desktop 실행 후
& .\experiments\db-split\scripts\build-a.ps1
# 위 단계는 Testcontainers로 별도 MySQL을 띄워 안전성 테스트를 수행합니다.
docker compose -f .\experiments\db-split\compose.yml up -d --wait
& .\experiments\db-split\scripts\start-a.ps1
```

두 서버 창에서 `Started ExperimentApplication`을 확인한 뒤:

```powershell
# jmeter.bat이 PATH에 없다면 실제 설치 경로로 바꾸세요.
$jmeter = 'C:\tools\apache-jmeter-5.6.3\bin\jmeter.bat'
& .\experiments\db-split\scripts\run-a.ps1 -Jmeter $jmeter -Warmup
& .\experiments\db-split\scripts\run-a.ps1 -Jmeter $jmeter
```

지금은 예열 1회와 점검용 본 측정 1회만 합니다. 최종 비교는 B/C 구현 후 같은 최종 커밋으로 A/B/C 순서를 교차해 구성별 최소 5회 수행합니다. 각 실행은 새 run ID를 사용하며 기존 데이터는 삭제하지 않습니다.

빌드 스크립트는 Git 커밋과 JAR 해시를 저장합니다. 시작 스크립트는 오래된 JAR과 미커밋 실험 코드를 거부합니다. 앱 중지는 해당 두 서버 창의 Ctrl+C를 사용합니다. 실험 DB 중지는 `docker compose -f .\experiments\db-split\compose.yml stop`입니다. 볼륨 삭제 명령은 사용하지 않습니다.

## 고정한 부하

- 실행별 학생 500명, 과목 1만 개, 신청 대상 5개 × 정원 100명
- 사용자 500명이 5초 동안 진입. 목록 2페이지 → 과목명 검색 → 신청. 마감이면 재조회하며 최대 3회 신청
- 첫 신청은 인기 과목에 집중. 대안 과목 선택 규칙도 구성별 동일
- 배경 조회는 **서버당 학사 조회 25 RPS + 신청용 조회 25 RPS**. 두 서버 합계 목표 100 RPS
- Baseline 90초, Mixed 90초. Mixed 시작 5초 후 사용자 진입
- 취소와 같은 학생 중복 요청은 성능 시나리오에 섞지 않고 별도 안전성 테스트

JMeter 배경 조회는 25개 스레드/유형/서버에 목표 속도를 부여한 **closed-loop**입니다. 느려지면 실제 전송량이 줄 수 있습니다. 전송하지 않은 요청을 성공으로 세지 않습니다. 실제 RPS가 유형별 24 미만이면 비교 유효성 경고를 남깁니다. 이는 서버가 목표 부하 전체를 처리했다는 증거가 아닙니다. 성공자 P95와 전체 사용자 결과를 함께 봅니다.

실제 업무 전체를 재현한 환경은 아닙니다. 사용자의 think time, 학적 변경, 여러 학기, 다중 시간대 과목, 인터넷 구간은 제외했습니다. 검색 응답은 이 실험의 단일 시간대 과목 기준 최대 50행입니다.

## 계측 해석

| 출력 | 의미 |
|---|---|
| 성공률 / 전체 outcome | 성공, 시도 제한, 업무 거절, 오류, 응답 불명을 구분 |
| 성공 여정 P95 | 첫 조회부터 신청 성공까지. 실패 사용자는 별도 집계 |
| 학사 / 신청용 조회 P95 | 종류별·서버별 분리. 배경 조회와 사용자 조회도 분리 |
| `sharedPool.acquire` | Hikari 연결 획득 소요. A에서는 학사·신청이 공유하는 풀 |
| `sharedPool.usage` | 연결을 빌린 후 반환까지의 시간. 트랜잭션과 SQL 실행 포함 |
| `studentLock.sql`, `courseLock.sql` | 잠금 SQL 왕복 전체. 순수 락 대기 시간은 아님 |
| `courseLock.holdUntilCompletion` | 잠금 SQL 반환 후 트랜잭션 완료 콜백까지의 근사 점유 시간 |
| `enrollment.transactionProxy` | 연결 획득부터 커밋/롤백까지 포함하는 서비스 호출 구간 |

서버 계측은 요청별 로그 대신 누적 카운터로 수집합니다. before/after 차이로 횟수·평균·합계를 계산합니다. `lifetimeMaxMs`는 프로세스 전체 최대이므로 본 측정의 최대값으로 해석하지 않습니다. 정확한 P95는 JMeter 원시 요청에서 계산합니다. DB 락 대기의 원인을 확정하려면 추가로 MySQL 잠금 대기 자료가 필요합니다.

MySQL 예산은 CPU 2개 / 메모리 2 GiB / 버퍼 풀 512 MiB입니다. 앱 JVM은 각각 512 MiB heap, DB 풀 각각 10개입니다. JVM CPU는 별도로 제한하지 않으므로 같은 호스트에서 다른 부하를 중단하고 실행 조건을 기록합니다. B에서는 두 MySQL의 **합계 예산**을 A와 맞춰야 합니다. 컨테이너 분리를 물리적 호스트 분리라고 설명하지 않습니다.

계측 자체의 오버헤드는 아직 측정하지 않았습니다. 필요하면 두 서버를 종료하고 `start-a.ps1 -MetricsOff`로 같은 조건을 재실행해 확인합니다. 이 결과는 본 비교와 별도로 분류합니다.

실행마다 데이터가 추가되므로 run ID 필터와 총 과목 행 수를 기록합니다. run별 1만 건은 DB 전체가 항상 1만 건이라는 뜻이 아닙니다. 데이터 증가와 캐시 예열 효과를 줄이기 위해 최종 A/B/C는 교차 순서로 반복합니다.

## 결과 전달

`experiments/db-split/results/A-measured-...`의 아래 파일을 전달하면 됩니다.

1. `summary.json` — 결과와 유효성 경고
2. `conditions.json` — 커밋, 서버 설정, 이미지와 자원 예산
3. `database.json` — 정원, 신청 집계, 학점, 시간표 정합성

오류가 있으면 `mixed.log`도 전달하세요. JTL과 `journeys.json`에는 요청 ID가 남아 불명 응답을 추적할 수 있습니다. 실패 회차를 삭제하거나 성공한 회차만 선택하지 않습니다.
