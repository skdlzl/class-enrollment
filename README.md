# Class Enrollment

수강신청 동시성 문제를 단계적으로 재현하고 Redis Redisson 분산 락의 선택 근거를 검증하기 위한 프로젝트입니다.

## 현재 구현

- Redis를 적용하기 전 기본 수강신청 화면
- 학생 선택, 과목 검색 및 필터
- 수강신청과 취소
- 학점 제한과 시간 중복 검증
- 테스트 데이터 초기화
- Java 17 + Spring Boot 3.5.16 기반 백엔드
- 서버 상태 확인 API와 컨트롤러 테스트
- Docker Compose 기반 MySQL 8.4
- Flyway 기반 스키마와 예시 데이터 관리
- Student, Course, CourseSchedule, Enrollment JPA 매핑

현재 수강신청 데이터는 브라우저 메모리에서 동작합니다. 백엔드는 이번 단계에서 독립적으로 실행되며 다음 단계에서 MySQL과 프론트엔드를 연결합니다.

## 실행

`dist/index.html`을 정적 서버로 실행합니다.

```bash
python3 -m http.server 4173 --directory dist
```

브라우저에서 `http://localhost:4173`으로 접속합니다.

### 백엔드

먼저 프로젝트 루트에서 MySQL을 실행합니다.

```bash
docker compose up -d mysql
```

IntelliJ에서 `backend/pom.xml`을 Maven 프로젝트로 연 뒤 `ClassEnrollmentApplication`을 실행합니다.

```http
GET http://localhost:8080/api/health
```

예상 응답:

```json
{
  "status": "UP",
  "service": "class-enrollment-backend"
}
```

## 다음 단계

1. Redis 없는 수강신청 API와 검증 로직 구현
2. 프론트엔드와 백엔드 연결
3. JMeter를 이용한 Race Condition 재현
4. DB 비관적 락 적용 및 병목 측정
5. Redis Redisson 과목별 분산 락 적용
6. 성능과 락 경합 지표 시각화
