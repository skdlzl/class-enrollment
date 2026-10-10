# 조건부 UPDATE 지연 분석

## 결론

조건부 UPDATE 구현에서 요청이 몰리면 **DB 연결 확보 대기와 같은 과목 행의 잠금 대기**가 함께 발생했다. 연결을 더 많이 허용하면 DB 안에서 같은 과목을 기다리는 요청이 늘었다.

다만 이번 Ubuntu 재측정에서는 조건부 UPDATE가 기존 Redisson 과목 락보다 빨랐다. 따라서 과거 Windows에서 기록한 평균 22.3초를 조건부 UPDATE 방식 자체의 성능으로 일반화하거나 이번 측정으로 그 당시의 원인을 확정할 수 없다.

## 실제 코드의 처리 순서

원본 조건부 UPDATE 커밋: `d2c9aec979ec1aca7e23b4e935dcd2a0edd32007`

학생 Redis 락 획득 → 트랜잭션 시작 → 학생 조회 → 과목 조회 → 중복 신청/학점/시간표 검사 → 조건부 UPDATE → 신청 INSERT → 커밋 → 학생 락 해제.

```sql
UPDATE courses
SET enrolled_count = enrolled_count + 1
WHERE id = :courseId AND enrolled_count < capacity;
```

- 신청 검사는 UPDATE 전에 끝난다. 검사 동안 과목 행 잠금을 잡는 구조라고 설명하는 것은 코드와 맞지 않는다.
- UPDATE로 자리를 확보하면 신청 INSERT와 커밋이 끝날 때까지 과목 행 잠금을 유지한다.
- UPDATE를 기다리는 요청은 자신의 DB 연결을 사용 중이다. 다른 요청들은 사용할 연결이 나올 때까지 기다릴 수 있다.
- 정원이 마감돼도 각 요청은 검사 후 UPDATE를 실행한다. 이번 실험에서도 조건부 UPDATE는 500회 실행됐다. 기존 Redis 과목 락 구현의 과목 UPDATE는 성공한 100회였다.
- 기존 `methodBodyMs`는 Service 메서드 내부에서 시작하고 끝난다. Spring 트랜잭션 프록시가 메서드 호출 전후에 하는 작업까지 포함하는 전체 요청 시간과 구분해야 한다. 이번에는 JDBC의 연결 확보와 SQL 실행 및 커밋을 직접 기록했다.

## 같은 환경에서의 재측정

기존 Redisson 구현은 `6ebba4d65808e947bc67a0e39afacfec66e51443`에 고정했다. 업무 코드와 SQL 및 트랜잭션 경계는 변경하지 않았다. 두 구현에 동일한 JDBC 관찰 코드를 추가했다.

- Ubuntu GitHub-hosted runner의 CPU 4개
- Java 17, JVM 2개, 포트 8080/8081
- 각 JVM `-Xms256m -Xmx512m`
- MySQL 8.4.11, 기본 REPEATABLE READ, Redis 7.4
- 같은 runner에서 부하 도구와 JVM 및 DB 실행
- 두 구현의 로그 수준을 root WARN으로 통일
- DB `atomic_analysis`를 별도 생성해 실제 사용 데이터와 분리
- 매 실행마다 JVM을 새로 시작하고 성공 요청 20건으로 준비한 뒤 신청 내역과 인원수 초기화
- 서로 다른 학생 500명, 과목 1개, 정원 100명, 두 JVM에 번갈아 요청
- 부하 도구는 Python urllib 500개 worker. 과거 Windows JMeter 측정과 실행 환경 및 도구가 다르다.
- JDBC 관찰 코드가 연결 확보/SQL 실행/commit/rollback/연결 사용 시간을 기록
- HikariCP 상태와 MySQL `performance_schema.data_lock_waits`를 0.05초 간격으로 조회
- SQL 실행 시간은 DB 잠금 대기와 DB 처리 및 통신 시간을 포함한다. 연결 확보 시간과는 별도다.

### 5초 동안 나눠 보낸 500건: 조건별 3회

총 9회 실행. 2회차는 실행 순서를 반대로 바꿨다. 아래 값은 각 회차 평균의 산술평균이다. P95도 회차별 P95의 산술평균이며 합친 1,500건의 P95와 구분한다.

- 기존 Redis 과목 락, JVM당 연결 10개: 평균 0.047초, 회차 P95 평균 0.240초
- 조건부 UPDATE, JVM당 연결 10개: 평균 0.010초, 회차 P95 평균 0.025초
- 조건부 UPDATE, JVM당 연결 50개: 평균 0.010초, 회차 P95 평균 0.030초

모든 조건에서 연결 확보 대기자가 관찰되지 않았다. 이 부하에서는 DB 연결이 포화되지 않았으며 과거 22.3초 지연이 재현되지 않았다.

측정 실행: https://github.com/skdlzl/class-enrollment/actions/runs/38047810554

상세 값: [ramp-summary.json](atomic-update-evidence/ramp-summary.json)

### 500건을 한꺼번에 보낸 추가 진단: 조건별 1회

실제 경쟁을 만들기 위해 시작 간격을 없앴다. 각 조건을 1회 측정했으므로 아래 수치는 관찰한 해당 실행의 결과다.

- 기존 Redis 과목 락, 연결 10개: 평균 응답 3.605초, P95 5.516초. DB 연결 확보 평균 0.000038초. 연결 대기자 최대 0명. 관찰된 DB 행 잠금 대기자 최대 0명.
- 조건부 UPDATE, 연결 10개: 평균 응답 1.824초, P95 2.621초. DB 연결 확보 평균 **1.492초**. UPDATE 실행 평균 **0.080초**. JVM당 연결 대기자 최대 **190명**. DB 행 잠금 대기자 최대 **17명**.
- 조건부 UPDATE, 연결 50개: 평균 응답 1.911초, P95 2.928초. DB 연결 확보 평균 **1.051초**. UPDATE 실행 평균 **0.557초**. JVM당 연결 대기자 최대 **150명**. DB 행 잠금 대기자 최대 **98명**.

연결 10개와 50개는 **JVM당** 설정이다. JVM 2개이므로 애플리케이션 연결 상한은 각각 20개와 100개다. MySQL 관찰 연결은 애플리케이션 풀 밖의 별도 연결이다.

MySQL 대기 기록에서 대상은 `courses` 테이블의 `PRIMARY` 인덱스와 과목 ID `1`이었다. 대기 중인 트랜잭션이 같은 과목의 `X,REC_NOT_GAP` 잠금을 요청하고 있었다.

즉 연결 수를 늘리면서 연결 확보 대기는 줄었지만 같은 과목의 UPDATE 대기는 늘었다. 동시에 대기할 연결을 더 허용해도 같은 과목 행을 동시에 수정할 수 있는 수는 늘어나지 않았다. 이번 실행에서 전체 평균과 P95도 소폭 증가했다. 반복 측정에 따른 성능 차이의 통계적 유의성은 이 1회 진단으로 판단하지 않는다.

측정 실행: https://github.com/skdlzl/class-enrollment/actions/runs/38048132827

상세 값: [burst-summary.json](atomic-update-evidence/burst-summary.json)

잠금 근거: [pool10-lock-waits.json](atomic-update-evidence/pool10-lock-waits.json), [pool50-lock-waits.json](atomic-update-evidence/pool50-lock-waits.json)

## 정합성

전체 12회 모두 HTTP 201은 100건, HTTP 409는 400건, HTTP 503은 0건이었다. 매회 DB 신청 내역과 `enrolled_count`도 100으로 일치했다. 예외 응답과 정합성 불일치가 있으면 측정 스크립트가 실패하도록 했다.

## 과거 Windows 수치와의 관계

당시 공유한 평균은 24.7초였고 로그를 줄인 뒤 22.3초였으며 연결 수 50개에서는 26.6초였다. 이 대화에서 공유한 측정 결과는 유지하되 당시 구간별 DB 관찰 자료와 동일 조건 비교가 없는 상태다.

찾은 `jmeter-final-results.zip`에는 19.3초인 초기 500건 결과, 1만 건 결과, 11.2초인 Redisson 최종 500건 결과가 들어 있었다. 22.3초 조건부 UPDATE 실행의 서버 로그와 JDBC/DB 대기 자료는 들어 있지 않았다. PR #4에도 해당 실행의 구간별 기록이 없었다.

이번 분석으로 확정한 내용은 조건부 UPDATE에서 경쟁이 DB 연결과 같은 행 잠금 대기로 나타난다는 점이다. 과거 Windows 22.3초 중 각 구간이 차지한 비율이나 Docker/디스크/CPU/로깅의 기여도는 아직 측정 근거가 없다.

## 설계 판단에 사용할 내용

조건부 UPDATE로 정원을 원자적으로 제어했다. 추가 분석에서 요청이 몰리면 같은 과목의 DB 잠금 대기와 연결 확보 대기가 함께 발생하는 것을 확인했다. 연결 수를 늘린 진단에서는 같은 과목을 기다리는 DB 요청이 늘었다. 따라서 연결 수 확대만으로 해결하기보다 DB에 진입하는 요청 수와 마감 요청의 불필요한 작업을 줄이는 방향을 검토한다.

기존 Redis 과목 락 구현은 DB 트랜잭션을 시작하기 전에 같은 과목 요청을 제한한다. 이 차이 때문에 이번 동시 요청 진단에서 DB 연결 대기와 행 잠금 경쟁이 거의 나타나지 않았다. 전체 응답시간은 Redis 대기를 포함하므로 DB 경쟁 감소와 전체 응답시간 개선을 각각 평가해야 한다.

MySQL 공식 잠금 동작: https://dev.mysql.com/doc/refman/8.4/en/innodb-locks-set.html

분석 코드와 근거는 Draft PR #8에 보관한다. 기존 구현 및 채택 브랜치와 Notion 포트폴리오는 수정하지 않았다.
