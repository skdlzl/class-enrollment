# DB 배치와 연결 풀 분리 실험

`experiment/db-split`의 독립 실험 모듈입니다. 기존 프론트와 큐·캐시 브랜치는 변경하지 않습니다. **A, B0, B, C와 학생 Redis 락만 사용하는 혼합 D를 구현했습니다.**

실험 질문은 분리합니다.

1. A → B0: 풀 분리와 앱 연결 수 증가가 조회에 어떤 영향을 주는가?
2. B0 → B: 같은 풀 구성에서 MySQL을 분리하면 학사 조회와 신청이 어떻게 달라지는가?
3. B → C: 같은 DB 배치와 안전장치에서 Redis 대기가 신청 DB의 연결 점유를 줄이는가?

## 고정한 자원 예산

연결 풀 수는 **앱 서버 한 대 기준**입니다. 앱은 두 대입니다.

| 구성 | MySQL | 버퍼 풀 합계 | 앱 연결 풀 | 앱 두 대의 최대 연결 수 |
|---|---|---|---|---|
| A | CPU 2 / 메모리 2GiB 한 대 | 512MiB | 공유 10 | 20 |
| B0 | A와 동일 | 512MiB | 학사 10 + 신청 10 | 40 |
| B | 학사와 신청 각각 CPU 1 / 메모리 1GiB | 각 256MiB, 합계 512MiB | 학사 10 + 신청 10 | 40 |
| C | B와 동일 + Redis CPU 0.5 / 메모리 256MiB | B와 동일 | 학사 10 + 신청 10 | 40 |

MySQL `max_connections`는 A/B0=100, B=각 50으로 합계 100입니다. 실제 앱의 풀 예산과 구분합니다. JVM heap은 서버마다 512MiB, Tomcat 최대 스레드는 200, DB 연결 획득 제한과 MySQL 행 잠금 대기 제한은 각각 10초입니다. 격리 수준은 READ COMMITTED입니다.

B의 신청 DB는 A보다 CPU·메모리 예산이 절반입니다. 컨테이너 두 개가 물리적 자원 격리를 뜻하지는 않습니다. 호스트 CPU, 디스크와 JVM/JMeter의 자원 경합은 남습니다. A → B0에는 풀 분리와 연결 수 증가가 함께 포함되므로 두 효과를 각각 분리했다고 주장하지 않습니다.

## 데이터와 신청 흐름

- 학사 영역: `academic_student`, `subject`
- 신청 영역: `student_term`, `offered_course`, `offering_schedule`, `enrollment`
- A/B0는 같은 DB 안에 두 영역을 둡니다. B/C는 두 MySQL 인스턴스에 각각 필요한 테이블만 둡니다.
- 준비 단계에서 학사 DB의 학생 자격과 최대 학점을 읽어 `student_term`으로 복제합니다. 신청 기간 전에 확정한 자격 정보입니다.
- **신청 중에는 학사 DB에 접근하지 않습니다.** 학생 행 잠금 → 학점·시간표 검증 → 과목 행 잠금 → 내역·인원·학점 갱신 → 커밋은 네 구성 모두 같은 `EnrollmentService`를 사용합니다.
- 데이터 준비의 학사 저장과 신청 DB 저장은 분산 트랜잭션이 아닙니다. 준비 도중 실패하면 해당 볼륨을 보존하고 새 세션에서 시작합니다. 불완전한 데이터로 측정하지 않습니다.
- 요청 ID 중복은 같은 결과를 반환합니다. 취소도 학생 → 과목 순서로 내역과 집계를 함께 변경합니다.
- 기간 중 학적·학점·시간표 변경과 취소 후 새 요청 ID로 같은 과목에 재신청하는 기능은 이번 실험 범위에서 제외합니다.

## 데이터 크기 고정

`start-comparison.ps1`은 매번 **고유 Compose 프로젝트와 새 볼륨**을 만듭니다. 기존 `53316` 실험 DB와 기존 결과는 건드리지 않습니다.

각 세션은 학생 500명과 과목 1만 개를 한 번만 생성합니다. 예열이 끝난 뒤 본 측정 전에는 해당 세션의 신청 내역과 학생·과목 집계만 초기화합니다. 학생과 과목은 유지하므로 DB 전체 과목 수가 항상 1만 개입니다. 두 서버의 부하가 끝나고 풀이 유휴 상태인지 확인한 뒤 초기화합니다. 테스트 도중 초기화 API를 호출하면 안 됩니다.

한 세션은 예열 1회와 본 측정 1회만 수행합니다. 본 측정 슬롯은 실행 전에 소모 처리합니다. 실패한 회차를 같은 세션에서 덮어쓰지 않습니다. 다음 회차는 다시 새 볼륨에서 시작합니다. 초기화된 신청 내역은 예열 데이터이며 결과 JTL/JSON은 보존합니다.

## 실행 — Windows PowerShell

Docker Desktop, Java 17, Node.js, JMeter **5.6.3**이 필요합니다. 다른 부하 테스트와 기존 신청 서버는 종료하세요. 예전 실험 MySQL도 자원을 쓰지 않도록 중지하되 볼륨은 삭제하지 마세요.

```powershell
cd C:\portfolio\class-enrollment
git switch experiment/db-split
git pull --ff-only

# 세 구성의 MySQL 안전성 테스트와 빌드. Docker Desktop이 켜져 있어야 합니다.
& .\experiments\db-split\scripts\build.ps1
```

먼저 B의 실행을 1회 점검합니다. JMeter 설치 경로는 실제 위치로 바꿉니다.

```powershell
$jmeter = 'C:\tools\apache-jmeter-5.6.3\bin\jmeter.bat'
& .\experiments\db-split\scripts\start-comparison.ps1 -Mode B -Round 1
& .\experiments\db-split\scripts\run-comparison.ps1 -Jmeter $jmeter -Warmup
& .\experiments\db-split\scripts\run-comparison.ps1 -Jmeter $jmeter
& .\experiments\db-split\scripts\stop-comparison.ps1
```

A는 `-Mode A`, B0는 `-Mode B0`로 시작합니다. 시작 스크립트가 서버 준비까지 기다립니다. 실패하면 출력된 세션 폴더의 `server-28080.log`, `server-28081.log`를 확인하세요. 중지 스크립트는 기록된 세션의 Java PID와 컨테이너만 중지하며 볼륨과 결과는 보존합니다. 다음 세션을 시작하기 전에 이전 세션을 중지해야 합니다.

앱 포트는 `28080`, `28081`입니다. 비교 DB는 A/B0=`53326`, B 학사=`53327`, B 신청=`53328`입니다. 기존 `start-a.ps1`, `run-a.ps1`은 데이터 누적을 막기 위해 사용을 중단했습니다.

### 최종 반복 비교

B 점검이 끝나면 같은 최종 커밋에서 전체를 실행합니다. 기존 4만 과목이 쌓인 A 결과는 참고 자료로 보존하고 최종 비교에 섞지 않습니다.

```powershell
& .\experiments\db-split\scripts\run-suite.ps1 -Jmeter $jmeter -Rounds 5
```

순서는 1회차 A → B0 → B, 2회차 B0 → B → A, 3회차 B → A → B로 순환합니다. 구성별 5회이므로 총 15세션입니다. 예열과 본 측정만 합쳐 약 90분이며 DB·서버 시작 시간이 추가됩니다. 자동 실행은 Windows에서 수행하며 매 세션의 자원 예산과 서버 설정을 검증합니다.

전송량 부족만 있으면 경고 결과를 포함해 계속합니다. 정합성 불일치, HTTP 오류, 응답 불명 또는 사용자 수 불일치가 있으면 결과를 보존하고 중단합니다. 해당 서버는 원인 확인을 위해 남겨 둡니다. 오류 회차를 버리거나 성공한 결과만 선택하지 않습니다.

## 같은 부하

- 사용자 500명이 5초 동안 진입
- 목록 2페이지 + 과목명 검색 → 신청. 마감 시 재조회하며 최대 3회 신청
- 신청 대상 5개 × 정원 100명. 첫 신청은 인기 과목에 집중하고 대안 선택 규칙도 동일
- 배경 조회: 서버당 학사 25RPS + 신청용 25RPS. 두 서버 합계 목표 100RPS
- 조회만 하는 Baseline 90초와 신청을 함께 하는 Mixed 90초
- 검색 필터와 SQL, 타임아웃, 재시도 정책은 세 구성에서 동일

배경 조회는 스레드에 목표 속도를 부여한 closed-loop입니다. 느려지면 실제 전송량이 줄어듭니다. `targetShortfallCount`는 25×90과 실제 표본 수의 차이이며 **예약된 요청의 생략 건수가 아닙니다.** 초기 진입 지연도 포함합니다. 목표 RPS와 실제 RPS를 항상 함께 비교합니다. 실제 RPS가 유형별 24 미만이면 `offeredLoadMet=false`로 표시합니다.

학사 조회에는 과목 기본 설명이 포함됩니다. 신청용 조회에는 학점·시간표·정원·현재 인원이 포함됩니다. 신청 여정의 조회와 배경 조회는 별도로 집계합니다. 신청 여정은 학사 조회를 필수 단계로 추가하지 않았으며 기존 신청용 목록·검색 흐름을 유지합니다.

## 결과를 판단하는 기준

1. **학사 조회 보호:** B0/B의 Mixed P95가 각 구성의 Baseline에 얼마나 가까운가? 실제 전송량이 달라지지 않았는가?
2. **신청 경로의 비용:** 신청용 조회 P95, 신청 성공까지 P95, 완료율과 시도 제한 사용자 수가 어떻게 변했는가?
3. **정합성과 원인:** 정원·학점·시간표·집계 검증을 통과했는가? 학사용/신청용 풀의 대기·점유 변화가 응답 시간 변화와 함께 나타나는가?

학사 조회 보호는 유효한 성과입니다. 다만 학사 조회가 느리다고 호스트 CPU 병목으로 단정하지 않습니다. 공유 HTTP 스레드와 부하 발생기, GC, 디스크도 원인 후보입니다. 이번 결과로 Redis의 우위를 미리 가정하지 않습니다.

## 계측의 의미와 한계

| 지표 | 의미 |
|---|---|
| `sharedPool.*` | A의 공용 풀 |
| `academicPool.*`, `enrollmentPool.*` | B0/B의 개별 풀 |
| `*.acquire` | 연결 획득에 걸린 시간 |
| `*.usage` | 연결 획득 후 반환까지. 해당 풀을 쓰는 조회·신청 전체 집계 |
| `*.timeout` | 해당 구간 연결 획득 타임아웃 수 |
| `studentLock.sql`, `courseLock.sql` | 잠금 SQL 왕복 전체. 순수 DB 락 대기 시간은 아님 |
| `courseLock.holdUntilCompletion` | 잠금 SQL 반환 후 트랜잭션 완료 콜백까지 근사 점유 시간 |
| `enrollment.transactionProxy` | 연결 획득과 커밋/롤백을 포함한 신청 서비스 호출 |

풀 active/waiting은 1초 간격 표본입니다. 순간 피크를 놓칠 수 있습니다. 서버 계측은 before/after 차이로 계산하며 `lifetimeMaxMs`는 프로세스 전체 최대입니다. 업무 거절도 서비스 호출의 실패 카운터에 포함되므로 서버 오류 수와 같지 않습니다.

클라이언트 P95는 관측된 JMeter 요청입니다. 전체 사용자 결과와 성공자 P95를 함께 봅니다. 서로 다른 모집단의 평균을 나눠 Redis 개선 상한을 계산하지 않습니다. 계측 오버헤드 비교가 필요하면 별도 세션을 `start-comparison.ps1 -MetricsOff`로 시작합니다. 이 결과는 본 비교에서 분리합니다.

안전성 테스트는 같은 학생 동시 신청, 정원 경쟁, 중복 요청, 롤백과 취소를 세 구성에 공통 적용합니다. 고정 데이터 재사용·초기화, 학생 정보 스냅샷, 두 DB 테이블 경계와 풀 계측도 검사합니다. DB 롤백 테스트를 서버 강제 종료 테스트로 설명하지 않습니다. 프로세스 종료와 Redis 장애 주입은 별도 후속 검증입니다.

## 전달할 결과

단일 실행은 출력된 `.../B-measured-...` 폴더의 `summary.json`, `conditions.json`, `database.json`을 보내주세요. 자동 반복은 `results/suite-.../comparison.json` 하나에 각 회차의 조건과 요약을 모읍니다. 원시 JTL과 로그는 세션별로 보존됩니다.

`valid=false`만 보고 정합성 실패로 판단하지 마세요. `integrityValid`, `outcomeCountValid`, `executionErrors`, `offeredLoadMet`, `fixedDatasetValid`를 따로 확인합니다.


## C: Redis 대기 + 기존 DB 안전장치

C는 **B와 같은 분리 DB**에서만 실행합니다. B0와 C를 직접 비교해 Redis 효과라고 해석하지 않습니다.
기존 큐 워커, 비동기 접수, 로컬 대기열, 마감 캐시는 추가하지 않습니다.

신청/취소 흐름:
1. 학생 Redis 락 획득
2. 과목 Redis 락 획득
3. 기존 EnrollmentService 프록시 호출: 학생 DB 락 → 검증 → 과목 DB 락 → 저장 → 커밋/롤백
4. 과목 Redis 락 → 학생 Redis 락 해제

Redis 대기 중에는 트랜잭션을 시작하지 않습니다. DB 락과 유일 제약은 그대로 유지하므로 Redis 락 만료나 우회 호출이 발생해도 DB가 최종 정합성을 보호합니다.
Redis 락 실패 시 DB 경로로 자동 우회하지 않습니다. 503 REDIS_LOCK_TIMEOUT / REDIS_UNAVAILABLE / REDIS_LOCK_INTERRUPTED로 구분합니다.
커밋 후 unlock 실패는 이미 확정된 응답을 실패로 바꾸지 않고 별도 지표로 남깁니다.
서버 강제 종료를 이 부하 실험으로 검증했다고 주장하지 않습니다.

두 Redis 락의 획득 대기 예산은 합계 10초입니다. 고정 lease 대신 watchdog 30초를 사용합니다.
Redisson 명령 타임아웃 3초와 네트워크 지연 때문에 실제 HTTP 응답이 정확히 10초에 끝난다는 보장은 없습니다.
기존 클라이언트 HTTP 10초 제한과 최대 3회 신청 정책을 유지합니다. 응답 유실은 SUBMIT_UNKNOWN으로 보존합니다.

비교 한계:
- C의 과목 Redis 락은 검증과 DB 연결 획득까지 포함합니다. DB 과목 락만 교체한 실험이 아니라 **트랜잭션 진입 전 직렬화**의 효과와 비용을 비교합니다.
- studentRedis.wait / courseRedis.wait와 각 holdUntilCompletion을 결과 metrics에 기록합니다. holdUntilCompletion은 락 획득 후 DB 완료까지이며 unlock 통신 시간은 제외합니다.
- DB의 courseLock.sql에는 SQL 실행/통신 시간이 포함됩니다. 순수 DB 락 대기 시간으로 표기하지 않습니다.
- Redis 자원 CPU 0.5 / 256MiB는 MySQL 예산 외 추가 비용이며 conditions.json에 별도로 기록됩니다. 동등한 전체 자원 실험이라고 주장하지 않습니다.
- Redis를 외부에서 기다려도 HTTP 스레드는 점유합니다. 조회 응답 개선은 보장되지 않습니다.
- Redis는 실험용 락만 저장합니다. Redis 재시작으로 락이 사라져도 DB 안전장치를 유지합니다.
- run-suite.ps1의 A/B0/B 회전은 그대로입니다. C는 별도 실행하며 최종 B/C 비교는 같은 리비전에서 순서를 교대합니다.

기존 비교 서버를 stop-comparison.ps1로 종료한 뒤 최신 코드를 빌드합니다.
아래 명령은 한 줄씩 완료를 확인하며 실행합니다.

```powershell
git pull --ff-only
& .\experiments\db-split\scripts\build.ps1
& .\experiments\db-split\scripts\start-comparison.ps1 -Mode C -Round 1
& .\experiments\db-split\scripts\run-comparison.ps1 -Jmeter $jmeter -Warmup
& .\experiments\db-split\scripts\run-comparison.ps1 -Jmeter $jmeter
& .\experiments\db-split\scripts\stop-comparison.ps1
```

C를 실행하는 목적은 성공률과 조회 응답을 유지하면서 신청 DB 연결 대기를 줄일 수 있는지 확인하는 것입니다.
좋은 결과만 남기지 않고 타임아웃, 결과 불명, 실제 RPS, 정합성도 함께 비교합니다.

Redisson 동작 참고: https://redisson.pro/docs/data-and-services/locks-and-synchronizers/

## D: 분리 DB + 학생 Redis 락 + 과목 DB 락

마지막 비교용 구성입니다. A/B0는 추가 측정하지 않고 B/C의 기존 결과를 보존합니다.
D는 B/C와 같은 두 MySQL 인스턴스, 풀 예산, 데이터, 부하, 클라이언트 제한을 사용합니다.
Redis 예산도 C와 동일한 추가 CPU 0.5 / 메모리 256MiB입니다.

학생 Redis 락 → DB 트랜잭션 → 학생 DB 안전 락 → 학점/시간표 검증 → 과목 DB 락 → 저장/커밋 → 학생 Redis 락 해제.
과목 Redis 락은 사용하지 않습니다. 학생 DB 안전장치와 취소/멱등 처리도 기존 Service를 그대로 사용합니다.
DB 연결을 잡지 않는 구간은 학생 Redis 락 대기뿐이며 과목 DB 락을 기다릴 때는 DB 연결을 점유합니다.
학생이 모두 다른 이번 부하에서 학생 Redis 락은 추가 비용이 될 수 있습니다. 개선을 보장하지 않습니다.

start-comparison.ps1 -Mode D -Round 1로 시작한 뒤 예열 1회와 본 측정 1회만 수행합니다.
실패한 결과도 보존합니다. 1회 관측과 리비전 차이의 한계를 명시하며 최종 우위가 입증됐다고 주장하지 않습니다.
C와 달리 서버 상태 redisGuard.course=false를 양 서버에서 사전 검증합니다.

요약 지표 수정:
- integrityValid: DB 인원 집계/학점/시간표/워크로드 중복 학생 검사
- responseCountMatchesDatabase: 클라이언트 성공 수와 DB 저장 수의 집계 일치 (요청별 대조는 별도)
- valid: 위 두 항목과 실행 오류, 부하 충족, 데이터 조건을 모두 확인

기존 결과 JSON은 다시 쓰지 않습니다. 이전 integrityValid에는 응답 집계 일치까지 포함됐으므로 해석 시 구분합니다.
