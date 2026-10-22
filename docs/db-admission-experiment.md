# 신청 트랜잭션 진입 제한 비교

혼합의 학생 Redis 락과 학생·과목 DB 안전장치는 유지합니다. 두 구성 모두 트랜잭션 프록시 호출 전에 서버별 공정 세마포어로 진입을 제한합니다. 슬롯은 프록시 반환 또는 예외 이후 반환하므로 커밋 및 롤백까지 포함합니다. 대기 중 DB 연결은 사용하지 않지만 HTTP 스레드와 혼합의 학생 Redis 락은 유지합니다.

설정 기본값은 limit=0(비활성), wait-ms=3000, max-waiting=500입니다. 실험은 서버당 limit=2로 시작합니다. 두 서버 합계 최대 4개 신청 쓰기 트랜잭션이며 조회 연결 8개를 예약하는 기능은 아닙니다. 다른 DB 작업과 Redis 모드의 사전 검증은 이 제한의 대상이 아닙니다. 기존 과목별 local-gate는 OFF로 유지합니다.

대기 시간 초과는 DB_ADMISSION_TIMEOUT, 대기 인원 상한 초과는 DB_ADMISSION_FULL, 인터럽트는 DB_ADMISSION_INTERRUPTED(모두 503)입니다. 재시도는 자동 수행하지 않습니다. HTTP 전체 처리 제한을 늘리지 않습니다. 여러 대기 구간의 합은 클라이언트 타임아웃을 넘을 수 있으므로 SUBMIT_UNKNOWN도 반드시 평가합니다.

/api/experiment/pool 및 /api/experiment/diagnostics의 dbAdmission에 설정과 활성 수, 대기 수, 누적 진입·거절·타임아웃·인터럽트, 대기 시간 합계·최댓값을 제공합니다. 값은 JVM 시작 이후 누적치이며 diagnostics 전후 차이로 회차별 건수를 계산합니다. maxWaitMs는 누적 최댓값으로 차감하지 않습니다.

서버 실행 인자에 다음을 추가합니다:

```text
--enrollment.db-admission.limit=2
--enrollment.db-admission.wait-ms=3000
--enrollment.db-admission.max-waiting=500
```

```powershell
node .\scripts\run-enrollment-journey.mjs PESSIMISTIC --search --read-rps 50 --db-concurrency 2 --warmup
node .\scripts\run-enrollment-journey.mjs PESSIMISTIC --search --read-rps 50 --db-concurrency 2
```

DB_ONLY 비교도 동일 서버 인자와 --db-concurrency 2를 사용합니다. --db-concurrency는 서버 설정을 변경하지 않고 일치 여부만 확인합니다. 조건 파일에 양쪽 서버 설정을 저장하고 summary의 diagnostics에 누적 상태를 저장합니다. 성능 효과는 미측정이며 신청 완료율, 완료 P95, 조회 P95, 전송 생략, 타임아웃과 응답 불명을 모두 평가합니다. 이 개선을 Redis 자체의 효과로 해석하지 않습니다.
