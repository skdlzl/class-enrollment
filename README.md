# Class Enrollment

수강신청 동시성 문제를 단계적으로 재현하고 Redis Redisson 분산 락의 선택 근거를 검증하기 위한 프로젝트입니다.

## 현재 구현

- React + TypeScript + Vite 기반 수강신청 프론트엔드
- 학생용 수강신청 화면과 관리자 확장용 `/admin` 라우트
- 학생 선택, 과목 검색 및 필터
- 수강신청과 취소
- 학점 제한과 시간 중복 검증
- Java 17 + Spring Boot 3.5.16 기반 백엔드
- Docker Compose 기반 MySQL 8.4
- Flyway 기반 스키마와 예시 데이터 관리
- Student, Course, CourseSchedule, Enrollment JPA 매핑
- Redis와 DB 락을 사용하지 않은 수강신청 API
- JUnit, Mockito, Testcontainers 기반 단위·통합·동시성 테스트

현재 프론트엔드 데이터는 브라우저 메모리에서 동작합니다. 백엔드 API와의 연결은 동시성 문제 재현 후 진행합니다.

## 프론트엔드 실행

```bash
cd frontend
npm install
npm run dev
```

학생용 화면:

```text
http://localhost:5173
```

관리자 확장 경로:

```text
http://localhost:5173/admin
```

프로덕션 빌드:

```bash
npm run build
```

## 백엔드 실행

먼저 프로젝트 루트에서 MySQL을 실행합니다.

```bash
docker compose up -d mysql
```

IntelliJ에서 `backend/pom.xml`을 Maven 프로젝트로 연 뒤 `ClassEnrollmentApplication`을 실행합니다.

상태 확인:

```http
GET http://localhost:8080/api/health
```

수강신청 요청:

```http
POST http://localhost:8080/api/enrollments
Content-Type: application/json

{
  "studentId": 1,
  "courseId": 1
}
```

현재 백엔드는 의도적으로 동시성 락을 사용하지 않습니다. 단건 요청의 비즈니스 규칙은 검증하지만 같은 과목에 요청이 몰리면 정원 정합성이 깨질 수 있습니다.

## 다음 단계

1. 락 없는 동시 수강신청 문제 재현
2. Redis Redisson 과목별 분산 락 적용
3. 동일 동시성 테스트로 정합성 재검증
4. 프론트엔드와 실제 백엔드 API 연결
5. JMeter 부하 테스트 및 성능 비교
6. Vercel 프론트엔드 배포
7. 성능과 락 경합 지표 시각화
