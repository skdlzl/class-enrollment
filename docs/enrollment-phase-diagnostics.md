# 동기 신청 병목 진단

처리 로직, 락 순서, 타임아웃을 변경하지 않고 구간 시간을 계측합니다. experiment.enabled=true에서만 수집합니다. DB 진입 제한은 서버에서 enrollment.db-admission.limit=0으로 끄고 실행 시 --db-concurrency 0을 지정합니다. 기존 local-gate도 OFF로 유지합니다.

## 지표의 범위

| 지표 | 실제 측정 구간 |
|---|---|
| transactionProxy | 최종 저장 서비스 프록시 호출부터 반환/예외까지. DB 진입 제한 대기는 제외 |
| transactionEntry | 프록시 호출부터 서비스 본문 시작까지. 트랜잭션 준비 및 연결 획득 등이 포함될 수 있음 |
| studentLockQuery | 학생 SELECT FOR UPDATE와 결과 변환. 순수 락 대기만이 아님 |
| courseRead | 일반 과목 조회 |
| validationQuery | 중복 신청, 학점, 시간표 검증용 집계 조회 |
| ruleValidation | 조회된 결과로 규칙 및 사전 정원 검사 |
| courseLockQuery | 과목 PESSIMISTIC_WRITE refresh. SQL, 락 대기, ORM 처리 포함 |
| saveCall | 신청 저장 호출 및 메모리 인원 갱신. 커밋 시점의 flush는 제외 |
| transactionBody | 저장 서비스 본문 전체. 하위 구간과 중복되는 총량 |
| completionTail.committed/rolledBack/unknown | 서비스 본문 종료부터 트랜잭션 afterCompletion까지 |
| commitCallbacks.* | beforeCommit부터 afterCompletion까지. flush 및 다른 콜백 포함. JDBC commit 단독 시간이 아님 |
| pool.acquire | Hikari 연결 획득 시간. 조회와 신청을 포함한 전체 DB 작업 |
| pool.usage | Hikari 연결 대여부터 반환까지. 전체 DB 작업, 밀리초 해상도 |

각 구간의 count, failures, totalMs, maxMs는 JVM 시작 이후 누적입니다. failures는 해당 계측 호출에서 예외가 발생한 횟수이며 업무상 거절도 포함합니다. transactionBody와 완료 콜백 지표는 시간 기록용이며 failures=0을 성공 건수로 해석하지 않습니다.

summary.json의 phaseTimings는 측정 전후 count/totalMs/failures 차이로 회차별 평균을 계산합니다. lifetimeMaxMs는 예열을 포함한 JVM 전체 최댓값입니다. 평균을 P95로 표기하지 않습니다. 구간별 대상 요청 수가 다르고 중첩 구간도 있으므로 평균을 모두 더하지 않습니다. 각 서버 스냅샷은 동시 요청에 대한 원자적 스냅샷이 아니며 종료 시 진행 중 요청은 일부 구간만 집계될 수 있습니다.

## HTTP 대기 해석

실험 필터에 동기 신청 POST /api/enrollments를 syncSubmit으로 추가했습니다. 필터 내부 HTTP 시간과 클라이언트 socketAssigned/connected/requestFlushed/headers 시간을 함께 확인합니다. 필터 시작 이전의 서버 스레드 대기, 클라이언트 연결 대기, 네트워크 및 OS 지연을 이 지표만으로 각각 정확히 분리할 수는 없습니다. 서로 다른 집계의 P95를 빼서 서버 큐 대기로 계산하지 않습니다.

실험기는 오래된 JAR의 계측 누락을 실행 전에 차단합니다. 진단 정보는 기존 diagnostics에도 원본 그대로 저장됩니다. 사용자별 로그나 무제한 표본은 보관하지 않고 고정 구간별 카운터만 사용합니다.

## 실행

서버 두 대에서 enrollment.db-admission.limit=0을 지정한 뒤 기존 혼합 설정을 유지합니다.

```powershell
node .\scripts\run-enrollment-journey.mjs PESSIMISTIC --search --read-rps 50 --db-concurrency 0 --warmup
node .\scripts\run-enrollment-journey.mjs PESSIMISTIC --search --read-rps 50 --db-concurrency 0
```

본 측정 summary.json과 conditions.json으로 구간별 지연을 확인합니다. 이 변경은 성능 개선 자체가 아니며 원인을 식별하기 위한 진단입니다.
