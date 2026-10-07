# Class Enrollment

**수강신청 동시성 문제를 재현하고, 학생·과목 분산 락과 대기열 설계를 검증한 개인 백엔드 프로젝트입니다.**

- 같은 과목에 몰리는 요청의 정원과, 같은 학생의 동시 신청에 대한 학점·시간표를 각각 보호했습니다.
- 과목 락 범위를 축소해 Windows 반복 측정에서 503 응답을 **579건 → 107건**으로 줄였습니다.
- 후속 로컬 대기열 후보는 Ubuntu CI에서 **회차별 P95 평균 10.41초 → 6.75초**, 매회 **신청 내역·인원수 100명 일치**를 확인했습니다.

## 기술 스택과 검증 환경

| 구분 | 구성 |
| --- | --- |
| 백엔드 | Java 17 · Spring Boot 3.5 · Spring Data JPA |
| 저장소 / 동시성 제어 | MySQL 8.4 · Redis 7.4 · Redisson |
| 검증 | JUnit 5 · Mockito · Testcontainers · JMeter 5.6.3 |
| 실행 / 자동화 | Docker Compose · GitHub Actions |
| Windows 부하 측정 | 동일 PC의 JVM 2개(8080·8081), MySQL·Redis·JMeter |
| 후속 후보 검증 | GitHub-hosted Ubuntu에서 JVM 2개와 동일 부하 절차 |

프론트엔드는 React · TypeScript · Vite로 학생 선택, 과목 검색, 신청·취소 화면을 구현했습니다. 현재 화면 데이터는 브라우저 메모리에서 관리합니다.

## 코드와 검증 자료

`main`에는 동시성 제어 전의 초기 구현을 보존했습니다. 아래 브랜치에서 단계별 코드와 검증 자료를 확인할 수 있습니다.

| 단계 | 브랜치 / 자료 | 내용 |
| --- | --- | --- |
| 초기 구현 | [main](https://github.com/skdlzl/class-enrollment/tree/main) | 비즈니스 규칙 구현, 동시성 문제 재현 출발점 |
| 분산 락 | [feat/redisson-lock](https://github.com/skdlzl/class-enrollment/tree/feat/redisson-lock) | Redisson 기반 학생·과목 보호 |
| 락 범위 축소 | [feat/reduce-course-lock-scope](https://github.com/skdlzl/class-enrollment/tree/feat/reduce-course-lock-scope) | 학생 검증을 과목 락 밖으로 이동 |
| 로컬 대기열 후보 | [feat/course-local-admission](https://github.com/skdlzl/class-enrollment/tree/feat/course-local-admission) · [PR #6](https://github.com/skdlzl/class-enrollment/pull/6) | 과목별 세마포어, 입장 후 마감 확인, 비교 측정 자동화 |
| 데드락 분석 | [분석 문서와 잠금 로그](https://github.com/skdlzl/class-enrollment/blob/test/mysql-deadlock-analysis/docs/mysql-deadlock-analysis.md) · [PR #7](https://github.com/skdlzl/class-enrollment/pull/7) | 외래키 S 잠금 → X 잠금 승격의 순환 대기 재현 |
| 조건부 UPDATE 실험 | [feat/atomic-capacity-update](https://github.com/skdlzl/class-enrollment/tree/feat/atomic-capacity-update) · [PR #4](https://github.com/skdlzl/class-enrollment/pull/4) | DB 조건부 UPDATE를 통한 정원 제어 실험 |

## 문제와 설계

### 1. 과목 락으로 정원 보호

같은 과목의 잔여 정원을 여러 요청이 동시에 읽으면 각 요청이 신청 가능하다고 판단할 수 있습니다. 과목별로 **조회 → 정원 확인 → 신청 저장 → 인원 증가 → 커밋**을 순차 처리하도록 설계했습니다.

`synchronized`는 한 JVM 안에서 순차 처리를 보장합니다. 애플리케이션을 두 JVM으로 실행하자 정원 2명인 과목에 신청 3건이 저장되는 상황을 재현했습니다. 두 인스턴스가 같은 Redis 락을 공유하도록 Redisson을 적용했습니다.

초기 구현의 DB 데드락도 별도로 분석했습니다. 신청 INSERT의 외래키 검증으로 같은 과목 행의 S(공유) 잠금을 보유한 트랜잭션들이, 인원 UPDATE를 위해 X(배타) 잠금을 요청하면서 서로를 기다렸습니다. UPDATE 직전 실행 시점을 맞춘 5개 요청에서 **성공 1건·데드락 롤백 4건**을 확인했고, 같은 Service의 트랜잭션 전체를 직렬화한 대조군에서는 **성공 2건·정원 마감 3건**을 확인했습니다.

### 2. 학생 락으로 학점·시간표 보호

과목 락만으로는 같은 학생이 서로 다른 과목을 동시에 신청하는 상황을 보호하기 어렵습니다. 두 요청이 같은 기존 학점과 시간표를 읽고 검증을 통과할 수 있기 때문입니다.

학생별 분산 락을 추가해 검증부터 저장·커밋까지 유지했습니다. 락 획득 순서는 **학생 → 과목**으로 고정했습니다.

| 보호 대상 | 검증 / 처리 |
| --- | --- |
| 학생 | 재학 상태, 중복 과목, 최대 학점, 시간표 중복 |
| 과목 | 정원 확인, 신청 저장, enrolled_count 증가 |
| 트랜잭션 | 신청 내역과 인원수 함께 커밋, 커밋 완료 후 락 해제 |

### 3. 과목 락 점유 범위 축소

구간별 로그에서 과목 락 대기는 약 8초, Service 처리 본문은 약 0.02초인 표본을 확인했습니다. 학생 관련 검증까지 과목 락 안에서 수행하던 흐름을 다음과 같이 나눴습니다.

1. 학생 락 획득
2. `validateForEnrollment()`: 학생 상태·중복·학점·시간표 검증
3. 과목 락 획득
4. `completeEnrollment()`: 과목 조회·정원 확인·신청 저장·인원 증가·커밋
5. 과목 락 해제, 학생 락 해제

Facade가 락을 관리하고, 별도 Service가 트랜잭션을 수행합니다. 트랜잭션 메서드가 커밋 후 반환되면 Facade에서 락을 해제합니다.

- [EnrollmentRedissonFacade.java](https://github.com/skdlzl/class-enrollment/blob/feat/reduce-course-lock-scope/backend/src/main/java/com/jiyun/classenrollment/enrollment/application/EnrollmentRedissonFacade.java)
- [EnrollmentService.java](https://github.com/skdlzl/class-enrollment/blob/feat/reduce-course-lock-scope/backend/src/main/java/com/jiyun/classenrollment/enrollment/application/EnrollmentService.java)

## 검증 결과

### Windows: 과목 락 범위 축소 전후

버전별 **500건씩 3회** 측정했습니다. 과목 1개·정원 100명에 고유 학생 500명이 신청하고, 5초 ramp-up으로 시작해 각 학생이 한 번 요청했습니다. 요청은 8080·8081 두 JVM으로 분산했습니다.

| 지표 | 기존 분산 락 | 과목 락 범위 축소 |
| --- | ---: | ---: |
| 총 요청 | 1,500 | 1,500 |
| 201 신청 성공 | 300 | 300 |
| 409 정원 마감 | 621 | 1,093 |
| 503 응답 | 579 | 107 |
| 회차별 평균 응답시간의 평균 | 9.39초 | 8.36초 |
| 회차별 P95의 평균 | 12.03초 | 12.20초 |
| 회차별 DB 신청 내역 / 인원수 | 매회 100 / 100 | 매회 100 / 100 |

주요 성과는 **503 응답 약 81.5% 감소와 정합성 유지**입니다. 전체 P95의 회차 평균은 12.20초였고, 후속 개선에서는 대기 방식과 정원 마감 요청 처리에 집중했습니다.

### Ubuntu CI: 로컬 대기열 후보

동일 JVM의 동일 과목 요청을 공정한 세마포어로 순차 입장시켰습니다. 입장한 요청은 새 읽기 트랜잭션에서 정원을 한 번 확인하고, 마감이면 409를 반환합니다. 빈자리가 있으면 Redis 과목 락을 획득한 뒤 저장 트랜잭션에서 정원을 다시 확인합니다.

이 구조는 **JVM별 Redis 과목 락 대기자를 최대 한 명으로 제한**합니다. 서버 간 정합성은 기존 분산 락으로 보호하고, 로컬 대기와 Redis 과목 락 대기에 합계 10초의 예산을 적용했습니다.

같은 JAR의 기능 off/on을 비교했습니다. 실행 순서를 교대하며 버전별 3회 측정했고, 매 실행마다 두 JVM 재시작·20건 예열·과목 초기화를 수행했습니다.

| 지표 | 기능 off | 기능 on |
| --- | ---: | ---: |
| 총 요청 | 1,500 | 1,500 |
| 201 신청 성공 | 300 | 300 |
| 409 정원 마감 | 1,105 | 1,200 |
| 503 응답 | 95 | 0 |
| 회차별 P95의 평균 | 10.41초 | 6.75초 |
| 회차별 P95 범위 | 10.31~10.50초 | 6.36~6.96초 |
| 회차별 DB 신청 내역 / 인원수 | 매회 100 / 100 | 매회 100 / 100 |

**전체 요청 P95의 회차 평균은 약 35.2% 감소**했습니다. 409 응답의 P95는 약 9.89~10.25초에서 6.37~6.98초로 줄었고, 201 응답의 P95는 약 6.23~6.41초에서 6.32~6.87초로 측정됐습니다.

Windows와 Ubuntu 결과는 각 환경의 비교로 구분했습니다. P95는 실행별 요청을 정렬해 계산하고, 위 표에는 세 회차의 P95를 산술평균한 값을 표시했습니다.

- [회차별 원본 수치·응답 종류별 결과·CI 실행 링크](https://github.com/skdlzl/class-enrollment/blob/feat/course-local-admission/docs/course-admission-ci-results.md)
- [측정 조건과 자동 실행 절차](https://github.com/skdlzl/class-enrollment/blob/feat/course-local-admission/docs/course-admission.md)

## 실험과 선택

| 시도 | 확인한 결과 | 판단 |
| --- | --- | --- |
| DB 조건부 UPDATE | 신청·인원수 100명 일치, 로그 축소 후 평균 22.26초 | 분산 락을 유지하고 락 범위 축소로 진행 |
| HikariCP 50개 | 해당 조건부 UPDATE 실험의 평균 26.63초 | 기존 풀 설정으로 원복 |
| 과목 락 범위 축소 | Windows 503 579 → 107건, 매회 정합성 일치 | 학생 검증과 과목 쓰기 구간 분리 |
| 주기적 DB 조회 | Windows 평균 15.55초, P95 18.56초, 503 384건 | 반복 조회 방식 제거 |
| 과목별 로컬 대기열 | Ubuntu CI P95 회차 평균 35.2% 감소, 503 95 → 0건 | 후속 후보 브랜치로 관리 |

각 실험은 해당 구현과 환경에서 측정한 결과입니다. 조건부 UPDATE와 풀 크기 변경의 지연 원인 계측, 로컬 대기열 후보의 Windows 반복 측정은 후속 검증 항목으로 남겼습니다.

## 실행 방법

Java 17, Docker Compose가 필요합니다. Docker 엔진을 실행한 뒤 **과목 락 범위 축소 브랜치**를 기준으로 백엔드를 실행합니다.

```bash
git clone https://github.com/skdlzl/class-enrollment.git
cd class-enrollment
git switch feat/reduce-course-lock-scope
docker compose up -d mysql redis
cd backend
```

Windows PowerShell:

```powershell
.\mvnw.cmd clean package
java -jar .\target\class-enrollment-backend-0.0.1-SNAPSHOT.jar --server.port=8080
```

macOS / Linux:

```bash
./mvnw clean package
java -jar target/class-enrollment-backend-0.0.1-SNAPSHOT.jar --server.port=8080
```

두 번째 터미널을 `backend` 디렉터리에서 열고 같은 JAR를 실행합니다.

```bash
java -jar target/class-enrollment-backend-0.0.1-SNAPSHOT.jar --server.port=8081
```

상태 확인: `GET http://localhost:8080/api/health`

프론트엔드는 별도 터미널에서 실행합니다.

```bash
cd frontend
npm install
npm run dev
```

학생 화면은 `http://localhost:5173`, 관리자 확장 경로는 `http://localhost:5173/admin`입니다.

로컬 대기열 후보의 부하 비교는 `feat/course-local-admission` 브랜치에서 [자동 측정 스크립트](https://github.com/skdlzl/class-enrollment/blob/feat/course-local-admission/scripts/measure-course-admission.ps1)를 사용합니다.

## 후속 검증

- 로컬 대기열 후보를 Windows 환경에서 반복 측정하고 P95·응답 코드·정합성 비교
- 조건부 UPDATE 실험의 DB 행 잠금·커넥션 대기·트랜잭션 구간 계측
- 개선 구현으로 50개 과목·10,000건 분산 부하 재검증
