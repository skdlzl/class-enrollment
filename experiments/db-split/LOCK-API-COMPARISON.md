# LOCK-API-v2: 외부 자격 조회와 혼합 락 비교

기본 자동 실행은 PESSIMISTIC / REDIS / HYBRID를 회차마다 순서를 바꾸어 3회 측정합니다.
각 실행은 새 데이터 볼륨에서 예열 후 본 측정을 수행합니다. OPTIMISTIC 구현과 안전성 테스트는 보존합니다.

| 구성 | 외부 API 대기 | 최종 저장 |
| --- | --- | --- |
| PESSIMISTIC | 학생 DB 락과 연결 유지 | 학생 검증 후 과목 DB 락 |
| REDIS | 학생 Redis 락 유지. DB 연결 반환 | 과목 Redis 락 후 짧은 DB 트랜잭션 |
| HYBRID | 학생 Redis 락 유지. DB 연결 반환 | 학생 DB 락으로 재검증 후 과목 DB 락과 저장 |

HYBRID는 과목 Redis 락 대기를 없앱니다. 학생 DB 락은 Redis 소유권이 유실되어 요청이 겹쳐도
학점과 시간표를 보호하기 위한 짧은 최종 안전장치입니다. 학생 → 과목 순서를 유지하며 DB 커밋 후 Redis 락을 해제합니다.
외부 자격 API가 반환한 정보가 저장 직전 변경되는 문제까지 해결하지는 않습니다.
세 구성의 안전 보장이 동일하지는 않습니다. 순수 REDIS는 기존처럼 lease 유실 구간을 DB 학생 락으로 방어하지 않습니다.
PESSIMISTIC도 API를 트랜잭션 밖으로 옮기고 재검증할 수 있으므로 이번 차이를 Redis 자체의 우위로 일반화하지 않습니다.

## 측정 수정

- opt-in LOCK-API 구성의 클라이언트 HTTP 제한은 모두 30초입니다. 서버 대기 예산은 기존 10초입니다.
- 서버 예산은 SQL을 강제로 취소하는 하드 데드라인이 아닙니다. 30초 후에도 응답 불명이 생길 수 있습니다.
- 타임아웃 확대만으로 완료율이 오를 수 있으므로 v1과 개선율을 직접 계산하지 않습니다. 세 구성을 v2에서 다시 비교합니다.
- 요청 오류에 예외 종류를 기록합니다. 응답 불명 요청은 임의로 재전송하지 않습니다.
- 두 서버가 유휴 상태가 된 뒤 request ID별 DB 신청 내역을 저장하고 대조합니다.
- summary의 outcomes와 successfulJourney는 실제 사용자 응답을 유지합니다. DB에서 뒤늦게 확인한 성공을 사용자 성공이나 P95에 합치지 않습니다.
- reconciliation.json의 confirmedAfterUnknown은 뒤늦게 확인한 저장 건수입니다. unknownWithoutStoredRecord는 관측 시점 미저장 건수입니다.
- drained-after.json은 유휴 확인 후 서버 상태이며 기존 mixed-after.json은 측정 구간 직후 상태입니다.
- valid:false 결과도 보존하며 정합성 오류가 있으면 자동 비교를 중단합니다.

## 실행

Docker Desktop 실행 후 PowerShell에서:

```powershell
cd C:\portfolio\class-enrollment
git switch experiment/db-split
git pull --ff-only
& .\experiments\db-split\scripts\run-lock-api-comparison.ps1
```

결과는 새 LOCK-API-3rounds-* 폴더와 ZIP에 저장됩니다. v1 결과에 이어서 실행하지 않습니다.
서버 2개와 MySQL 2개 및 1만 과목 검색 흐름은 그대로입니다. 외부 API는 기본 100ms 지연의 읽기 전용 모의 자격 API입니다.
완료율 개선은 아직 가설이며 실제 측정 결과로 판단합니다.

---

## v1 구현 기록 (아래 자동 실행 설명은 당시 기준)

# 외부 자격 확인이 포함된 수강신청 락 비교

## 비교 질문

동일한 수강신청 규칙에서 학생과 과목의 동시성을 Redis 분산 락 / DB 비관적 락 / DB 낙관적 락으로 제어할 때 완료율과 지연이 어떻게 달라지는가?

DB_CLAIM과 알림 발송은 이 실행 경로에서 제외합니다. 이전 실험 코드와 결과는 기록으로 보존합니다. 새로운 기능의 기본값은 OFF입니다.

## 공통 조건

- 실제 Spring Boot 서버 2개. 같은 PC에서 별도 JVM으로 실행합니다. 물리 호스트/운영 네트워크 분리 실험은 아닙니다.
- 학사 MySQL과 수강 MySQL을 분리합니다. 각 MySQL CPU 1 / 메모리 1GiB / 버퍼 풀 256MiB.
- 서버마다 학사 풀 10개와 수강 풀 10개. 서버 JVM 힙 512MiB. Redis 구성은 CPU 0.5 / 메모리 256MiB Redis를 추가합니다.
- 검색 과목은 고정 10,000개. 신청 대상은 정원 100명인 5개 과목. 학생 500명이 5초 동안 진입합니다.
- 기존 목록 2페이지 + 검색 → 신청 → 마감 시 다른 과목 검색/신청을 유지합니다. 사용자별 신청은 최대 3회입니다.
- 학사/개설 과목 배경 조회는 종류별 서버당 목표 25RPS. 합계 목표 100RPS인 closed-loop입니다. 실제 전송 RPS와 미달량을 함께 기록합니다.
- 조회 baseline 90초와 신청 혼합 90초. 각 실행은 예열 후 본 측정합니다. 3전략 × 3회 본 측정이며 회차마다 실행 순서를 회전합니다.
- 각 서버의 전략/예산/데이터셋과 PID를 사전 확인합니다. 두 서버는 반드시 같은 전략을 사용합니다.

## 외부 API

읽기 전용 모의 수강 자격 확인 API입니다. 기본 지연 100ms. 실제 알림이나 결제는 발생하지 않습니다. 고정된 eligible 응답을 HTTP로 읽습니다. 운영 자격 데이터의 변경이나 두 DB에 대한 분산 트랜잭션을 구현한 것은 아닙니다.

학생 최대 학점은 기존 student_term 스냅샷을 사용합니다. 외부 API는 추가 자격 조건입니다. 학생 검증과 과목 정원 검증의 규칙은 세 전략에서 동일합니다.

| 전략 | 외부 API 대기 중 | 과목 보호 | 쓰기 |
|---|---|---|---|
| REDIS | 학생 Redisson 락 유지. DB 연결 반환 | API/학생 검증 후 과목 Redisson 락 획득 | 짧은 DB 트랜잭션에서 갱신/저장. DB FOR UPDATE나 버전 CAS는 추가하지 않음 |
| PESSIMISTIC | DB 트랜잭션 안의 학생 행 FOR UPDATE와 연결 유지 | API/학생 검증 후 과목 FOR UPDATE | 같은 트랜잭션에서 갱신/저장 |
| OPTIMISTIC | 학생 버전을 읽고 연결 반환. 잠금 없이 API 호출 | 과목 버전을 읽고 저장 시 CAS | 학생/과목 버전 조건부 UPDATE와 내역 저장을 같은 트랜잭션으로 처리 |

API 호출 동안 과목 락은 잡지 않습니다. Redis 락은 DB 커밋/롤백 후 해제합니다. 모든 구성의 DB 쓰기는 실제 UPDATE 과정에서 InnoDB 행 락을 사용합니다. Redis/낙관적 락을 DB 내부 락이 전혀 없는 방식으로 표현하지 않습니다.

낙관적 락은 학생과 과목 양쪽의 version을 확인해야 합니다. 과목 버전만 확인하면 같은 학생이 다른 과목을 신청하는 학점/시간표 경쟁을 막지 못합니다. 충돌 시 트랜잭션 전체가 롤백되고 학생 읽기와 외부 API 호출부터 다시 수행합니다. 최대 5시도(재시도 4회)이며 10~30ms 지터를 사용합니다. 사용자별 최대 3회 과목 선택과 내부 낙관적 재시도는 다른 지표입니다.

## 예산과 실패

요청의 락 대기/재시도 예산은 10초이며 API 단일 호출 제한은 2초입니다. 진입/재시도/쓰기 전 예산을 검사합니다. DB 연결 대기와 진행 중 SQL을 10초 시점에 강제로 중단하는 하드 데드라인은 아닙니다. 실제 관측 응답 시간을 사용합니다.

API 거절은 409 ELIGIBILITY_DENIED. API 오류/타임아웃은 503이며 저장하지 않습니다. 낙관적 재시도 소진은 503 OPTIMISTIC_RETRY_EXHAUSTED. 모든 타임아웃/재시도 실패를 완료율 계산에 포함합니다. DB 커밋 응답 유실은 요청 ID로 대조해야 합니다.

이 비교는 신청 전용입니다. 취소 API는 비교가 켜진 동안 거부합니다. 기존 비관적 락 취소 경로를 Redis 신청과 섞으면 학생 직렬화 규약이 달라지기 때문입니다. 테스트 데이터 초기화는 양쪽 서버가 유휴일 때만 수행합니다.

## Redis 안전성 범위

Redis 구성은 watchdog과 해제 시 소유권 확인을 사용합니다. DB 쓰기 직전에도 학생/과목 락 소유권을 확인합니다. **이 확인은 fencing이 아닙니다.** 확인 직후 락이 만료되거나 Redis failover로 소유자가 겹치는 경우까지 정상 동시성 보장을 확장하지 않습니다. DB의 유니크/범위 CHECK는 동일하게 유지하지만 시간표 조건 전체를 보장하는 장치는 아닙니다.

따라서 Redis 구성은 정상 락 소유권이 유지되는 조건의 비교입니다. 장애에서도 강한 정합성이 필요한 운영 구성에 그대로 적용하거나 DB 보호를 추가한 혼합 구성과 같은 안전성이라고 주장하지 않습니다. 임의의 Redis 락 손실/OS 강제 종료/failover는 이번 검증 범위 밖입니다.

## 계측

기존 summary.json에 성공률 / 완료까지 P95 / 조회 P95 / 실제 RPS / DB 정합성을 보존합니다. metrics에는 다음이 추가됩니다.

- eligibility.calls / eligibility.http: 외부 API 시도 수와 HTTP 시간. 낙관적 재시도도 포함.
- optimistic.attempt / optimistic.conflict / optimistic.retry: 내부 시도/충돌/재시도.
- studentLock / courseLock 또는 studentRedis / courseRedis: 락 대기와 점유 시간.
- enrollmentPool.acquire / enrollmentPool.usage: 연결 획득 대기와 점유 시간.
- enrollment.transactionProxy / enrollment.write / lockApi.request: 트랜잭션과 쓰기 및 전체 서버 처리.

모의 공급자의 호출 카운터도 별도로 저장합니다. runs.csv에 API 호출과 재시도 및 연결 점유 합계를 포함합니다. averages.csv는 회차별 P95의 평균이며 전체 표본을 합친 P95가 아닙니다. 성공한 요청 P95만 보지 말고 실패율과 함께 판단합니다.

API를 트랜잭션 밖으로 옮길 수 있는 자격 정책/재검증 설계는 별도 대안입니다. 이 비교에서 Redis가 좋아져도 모든 비관적 락 설계보다 우월하다고 결론 내리지 않습니다. API 지연 효과를 분리하려면 별도 묶음으로 -ApiDelayMs 0을 실행할 수 있지만 기본 자동 실험은 100ms 한 조건입니다.

## 실행

Docker Desktop 실행 후 PowerShell:

```powershell
cd C:\portfolio\class-enrollment
& .\experiments\db-split\scripts\run-lock-api-comparison.ps1
```

기본 JMeter 경로는 C:\tools\apache-jmeter-5.6.3\bin\jmeter.bat 입니다. 다르면 -Jmeter로 지정합니다. 스크립트가 빌드/안전성 검사 → 모의 API 시작 → 서버 2개 시작 → 예열/측정 → 종료/전략 변경을 수행합니다. 전체 실행은 약 1시간 이상 걸릴 수 있습니다. 다른 부하 실험과 동시에 실행하지 않습니다.

진행 중 결과를 지우거나 실패 실행을 자동 재실행하지 않습니다. 정합성/데이터 오류는 중단합니다. 타임아웃과 부하 미달은 경고와 함께 보존합니다. 완료된 회차는 -Resume -Bundle <기존 경로>로 건너뛰되 중단된 회차는 원인 확인 없이 덮어쓰지 않습니다. transcript를 닫은 뒤 압축합니다. 결과 파일은 LOCK-API-3rounds-*.zip 입니다.

## 검증

Testcontainers 통합 테스트로 세 전략의 학점/시간표/정원 경쟁과 멱등 재요청을 확인합니다. API 거절/오류/타임아웃 시 미저장과 INSERT 실패 시 양쪽 카운터 롤백을 검사합니다. API 대기 중 연결 점유 차이도 직접 확인합니다. 낙관적 충돌을 제어해서 추가 API 호출과 전체 트랜잭션 재시도를 확인합니다.

CI는 실제 Spring Boot JVM 두 개를 전략별로 띄웁니다. 같은 학생이 서로 다른 과목을 양쪽 서버에 동시에 신청했을 때 성공 1건과 학점 초과 거절 1건 및 최종 DB 정합성을 확인합니다. CI 스모크는 500명 성능 측정을 대체하지 않습니다.
