# Windows 조건부 UPDATE 진단

이 스크립트는 사용자 Windows PC에서만 측정한다. GitHub Windows 작업은 PowerShell 5.1 문법과 결과 집계 및 Java 컴파일을 검사하며 실제 Docker 부하 측정은 하지 않는다.

## 실행

Java 17 또는 Java 21, Docker Desktop Linux 엔진, 기존 JMeter 5.6.3 경로가 필요하다. 기존 8080/8081 서버는 종료해 불필요한 자원 경쟁을 줄인다. 기존 프로젝트의 작업 브랜치는 그대로 유지한다.

```powershell
cd C:\portfolio\class-enrollment
git fetch origin
git worktree add --detach ..\class-enrollment-atomic-analysis origin/test/atomic-update-latency-analysis
powershell -NoProfile -ExecutionPolicy Bypass -File ..\class-enrollment-atomic-analysis\scripts\measure-atomic-update-windows.ps1
```

이미 위 분석 worktree가 있으면 새로 만들지 않고 해당 경로에서 최신 진단 브랜치를 받는다.

```powershell
git -C C:\portfolio\class-enrollment-atomic-analysis fetch origin
git -C C:\portfolio\class-enrollment-atomic-analysis switch --detach origin/test/atomic-update-latency-analysis
powershell -NoProfile -ExecutionPolicy Bypass -File C:\portfolio\class-enrollment-atomic-analysis\scripts\measure-atomic-update-windows.ps1
```

기본 실행은 기존 Redis 과목 락, 조건부 UPDATE 연결 10개, 조건부 UPDATE 연결 50개를 각각 3회 측정한다. 각 회차 500건을 5초 동안 시작하고 학생은 모두 다르다. JVM당 연결 수를 설정하므로 두 JVM의 총 연결 상한은 20개와 100개다. 짝수 회차는 순서를 반대로 바꾼다.

각 조건의 진단 1회만 필요할 때는 `-Rounds 1`을 추가한다. 시작 간격 없이 몰아 보내는 조건은 `-RampSeconds 0`으로 별도 실행한다. 두 조건의 수치는 각각 비교한다.

## 자동으로 수행하는 작업

- 원본 두 커밋의 코드를 임시 worktree에서 빌드하고 동일한 JDBC 관찰 코드 추가
- 별도 MySQL과 Redis 생성. 기존 프로젝트 DB와 Redis는 사용하지 않음
- JVM 2개를 18080/18081에서 실행. MySQL 13306과 Redis 16379 포트 사용
- 같은 JVM 메모리 설정과 root WARN 로그 수준 사용. 실제 JVM/DB 버전과 PC 및 Docker 자원 설정 기록
- 실행마다 JVM을 재시작해 동일한 성공 요청 20건으로 준비한 뒤 실험 DB 초기화
- JMeter에서 500건 실행. CSV는 따옴표 없이 생성하고 URL 포트를 `${port}`로 지정
- HTTP 응답과 오류 본문, JDBC 연결 확보 시간, SQL 실행 시간, 커밋 시간, 연결 사용 시간 기록
- HikariCP 풀 상태 0.05초 간격 기록. MySQL 잠금 대기는 0.25초의 조회 간격을 두고 관찰. Docker 명령 실행 시간이 더해지므로 실제 간격은 결과의 타임스탬프로 확인
- JVM별 CPU 사용량과 최대 메모리 및 GC 로그 저장
- 성공 건수와 DB 신청 내역 및 인원수 일치 여부 검사
- 측정 파일 저장 후 스크립트가 만든 JVM과 컨테이너 및 임시 소스만 정리

연결 대기와 SQL 실행 시간은 요청 전체 시간과 각각 비교한다. UPDATE 실행 시간에는 DB 행 잠금 대기와 쿼리 처리 및 통신 시간이 포함된다. 관찰된 대기자 최대값은 조회 시점의 최대값이며 모든 순간의 정확한 최대값과 구분한다.

## 결과

`class-enrollment-atomic-analysis/jmeter/results/windows-atomic-날짜시간.zip` 파일이 생성된다. 최종 출력에 전체 경로를 표시한다.

- `summary.json`: 회차별 평균/P95, 상태 코드, 연결 대기와 UPDATE 시간, 대기자 수, 정합성
- `environment.json`: PC, Docker, Java와 측정 설정
- 각 회차 `http.jtl`, `errors.xml`, `mysql-pool-waits.tsv`, `first-lock-waits.tsv`, `final-state.json`
- 각 JVM `jdbc.csv`, `pool.csv`, `gc.log`, `server.log`, `stderr.log`

오류가 생겨도 가능한 측정 파일을 압축해 보존한다. HTTP 500 등 예상 외 응답은 진단 대상으로 보관하고 다음 비교를 진행한다. 정합성이 어긋나거나 측정 자료가 누락되면 중단한다. 성공이 100건 미만이면 503/500 응답과 실제 DB 인원수를 함께 확인한다.

과거 Windows 22.3초를 이번 측정의 기본 조건으로 간주하지 않는다. 실행 도구는 JMeter로 맞췄지만 JVM 메모리와 로그 수준 및 별도 DB 사용을 명시했다. 느린 현상이 재현되면 연결 대기, UPDATE 시간, 잠금 대기 기록과 GC를 함께 확인한다.

사용자 PC의 기존 Java 21을 그대로 사용할 수 있다. 같은 실행에서 두 구현은 동일한 `java` 명령으로 실행되며 실제 버전을 `environment.json`에 기록한다. Ubuntu Java 17 결과와 Windows Java 21 결과는 각 환경 안에서 비교한다.
