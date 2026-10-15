# 과목 조회 + 수강신청 혼합 부하

## 목적과 선택 근거

검증할 가설: DB 비관적 락 대기가 같은 DB 연결 풀을 사용하는 조회의 연결 확보/응답 지연을 키우는가? Redis가 조회 지연을 줄여도 신청 실패가 늘어나면 함께 평가한다. 결과가 나오기 전까지 Redis 우위로 적지 않는다. DB 풀 축소, SQL SLEEP, 트랜잭션 지연 주입은 하지 않는다.

## 실제 조회 API

`GET /api/courses?year=2026&semester=2&department=CS&afterId=100000&size=20`

학년도/학기/학과 필터, ID 커서 페이징(기본20, 최대100), 과목명/교수/학점/정원/신청인원. 조회는 기존과 동일한 JdbcTemplate/DataSource를 사용한다. `idx_courses_catalog(academic_year,semester,department,id)` 인덱스 사용을 위한 V3 마이그레이션 추가. 존재하지 않는 학과는 빈 페이지, 잘못된 범위는400. 인위적인 지연이나 전체 테이블 집계는 없다.

## 데이터

`setup-load-test.sql`의 부하용 학생10000명/과목50개와 별개로 `setup-catalog-test.sql`은 다음 전용 범위를 생성한다.

- 과목20000개: ID100000~119999, 5개 학과, 교수200명, 과목별 시간표1개.
- 조회용 학생10000명: ID200000~209999.
- 기존 신청20000건: 학생별2과목, 과목별1명.

재실행 시 중복 신청/시간표가 추가되지 않는다. 부하용 학생은 각 요청에서 실제 학생/학점/중복 검증을 수행하며 샘플 이력은 별도 학생의 이력이다. 데이터는 Flyway로 자동 주입하지 않고 실험 스크립트에서 명시적으로 준비한다. 기존 부하 테스트 데이터 초기화는 매회 동일하며 조회용 데이터는 유지한다.

## 서버 내부 계측

`experiment.enabled=true`에서만 PoolTelemetry가 켜진다. 전용 스케줄러가1초마다 로그 `POOL_SAMPLE`을 남긴다. HTTP 요청/DB 쿼리로 수집하지 않는다. active/idle/waiting/total 및 Hikari가 측정한 성공한 연결 확보 소요시간의 구간 count/total/max와 타임아웃 건수 기록. acquireMaxMs는 DB 행 잠금 대기시간이 아니며 실패한 확보 시도의 시간은 포함하지 않는다. 여러 API의 연결 확보가 합쳐진 값이다. 평균은 구간 평균을 평균내지 않고 total/count로 계산한다.

스케줄러도 JVM/CPU/GC 영향을 받으므로 누락 여부와 표본 수를 표시한다. 기존 metrics tracker가 이미 있으면 덮어쓰지 않고 실행을 중단한다. 메트릭 설치는 실험 모드에만 적용된다.

## 부하와 비교 구간

JMeter5.6.3 Open Model Thread Group 사용. 서버별 목표10RPS의 random_arrivals, 고정 seed8080/8081, 40초간 조회 도착을 예약. 응답 완료를 기다린 뒤 다음 요청을 보내는 closed-loop가 아니다. 초당 정확히10건을 보장하지 않으며 실제 시작 요청률과 초별 건수를 저장한다. 목표와20% 이상 차이면 경고한다. 발생기 자원 부족도 별도 확인한다.

조회는 CS학과에서100개 커서를 순환한다. 조회마다20행씩 반환한다. 조회 HTTP 응답 제한15초, 마지막 요청 완료를 위한20초 drain(새 도착 없음). 실패/타임아웃을 별도 집계한다. 타임아웃된 요청을 성공 응답의 P95에 합치지 않는다.

- Baseline: 조회만 실행.
- Mixed: 같은 조회 스케줄 + 시작10초 후500학생/1과목/정원100 신청을5초에 걸쳐 투입.
- 모든 조건의 조회 비교 구간은 기준 sampler 시작 후10~40초에 **시작한** 요청. 신청은 전체 결과를 별도 집계.
- 신청이40초 이후까지 이어지면 경고, 한쪽만 비교 구간을 바꾸지 않는다.
- 실제 조회 시작률/실패율/P95와 DB풀 계측을 함께 비교한다. 랜덤 도착이므로 동일 seed/설정이라도 OS 스케줄링에 따른 시각 편차가 있다.
- DB 풀, 학생락, 사전확인, 서버 스레드, DB/Redis 설정 동일. 처음은 양쪽 local gate OFF.
- 먼저 조회만 예열1회/기준1회, 두 방식 혼합 각1회로 동작과 자원 한계 확인. 의미 있는 차이가 있을 때만 동일 조건 반복으로 검증. 로컬대기열 ON 최종구성 확인은 이후 별도 판단.

## 실행 (Windows)

서버 종료 후 코드 갱신/재빌드 필요. 각 창에서 실행하며 두 번째는8081로 바꾼다. 로그 파일명은 실행기가 읽는 규칙과 일치해야 한다.

```powershell
cd C:\portfolio\class-enrollment\backend
.\mvnw.cmd -DskipTests package
java -jar .\target\class-enrollment-backend-0.0.1-SNAPSHOT.jar --server.port=8080 --enrollment.course-lock-strategy=PESSIMISTIC --enrollment.capacity-precheck=true --enrollment.local-gate-enabled=false --experiment.enabled=true --logging.file.name=logs/catalog-PESSIMISTIC-8080.log
```

별도 창:

```powershell
cd C:\portfolio\class-enrollment
.\scripts\run-catalog-comparison.ps1 -Strategy PESSIMISTIC -Scenario Baseline -Warmup
.\scripts\run-catalog-comparison.ps1 -Strategy PESSIMISTIC -Scenario Baseline
.\scripts\run-catalog-comparison.ps1 -Strategy PESSIMISTIC -Scenario Mixed
```

Redis는 서버2개 모두 종료 후 같은 JAR에 `REDIS`, 로그 `catalog-REDIS-8080.log`/`catalog-REDIS-8081.log`를 지정한다. 재시작 후 조회만 동일하게 예열하고 Mixed 실행한다. 첫 실행효과가 여전하면 두 방식 동일한 예열 규칙을 정해 다시 비교한다.

결과 폴더에는 원본JTL, 조건JSON, 응답 코드별 평균/P95, 실제조회RPS, 초별도착건수, 서버 내부 풀 표본/요약, DB 정합성 결과를 저장한다. HTTP pool 탐침은 새 JMX에서 제거했다. JMeter 전체 summary는 여러 요청 종류가 섞여 있으므로 성능 비교에 사용하지 않는다.

CI는 MySQL에서2만과목/2만신청 seed를 두 번 실행해 중복 여부와 커서 페이징/입력 검증을 확인한다. 실제JMeter5.6.3을 가짜HTTP서버에 연결해 open-arrival/CSV/시간기준 동작을 검사한다. CI smoke 결과는 성능 측정이 아니며 Windows 실행기 전체 부하 검증은 실제PC에서 진행한다.
