# Class Enrollment — concurrency rebuild

외부 학사 검증 API를 호출하는 수강신청 서비스입니다. **Redis 과목 락으로 외부 호출부터 DB 커밋까지 조율**하고, DB에는 정합성을 지키는 짧은 CAS 갱신을 둡니다. 학생 Redis 락은 사용하지 않습니다.

> Redis는 채택 후보/기본 실행 전략입니다. 실제 비교 결과 없이 성능 우위를 주장하지 않습니다. 이전 README의 타 브랜치 측정값은 이번 구현의 증거가 아니므로 제거했습니다.

## 실행

Java 17, Maven 3.9+, Docker Compose가 필요합니다.

```bash
docker compose up --build -d
curl -i http://localhost:8080/api/enrollments \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-1' \
  -d '{"studentId":1,"courseId":1}'
```

첫 성공은 201, 동일 키/동일 요청 재전송은 동일 enrollmentId와 200입니다. 다른 과목에 동일 키를 사용하면 409입니다. API는 8080/8081 두 인스턴스로 실행됩니다. 기존 프론트는 메모리 기반 데모이며 이번 API와 아직 연결하지 않았습니다.

외부 API는 `scripts/external_api.py`로 별도 프로세스에서 실행하는 **읽기 전용 자격 검증 시뮬레이터**입니다. 실제 학사 서비스 연동, 결제, 외부 좌석 예약을 완료한 프로젝트라는 뜻이 아닙니다. 외부 변경 작업에는 별도 멱등성 계약과 상태 조회/보상 설계가 필요합니다.

## 검증

```bash
mvn -f backend/pom.xml verify
# 아래 부하 테스트는 DB를 초기화하므로 반드시 폐기 가능한 전용 DB에서만 실행하세요.
docker compose up -d --wait mysql redis external
mvn -f backend/pom.xml -DskipTests package
pip install requests pymysql aiohttp
python scripts/benchmark.py --requests 12000 --concurrency 128 --rounds 3
```

부하 실행 전 compose의 api1/api2는 중지하세요. 스크립트가 동일 JAR로 두 JVM을 시작합니다. 세 전략 × 두 부하 유형 × 세 회차 = **216,000 HTTP 요청**을 계획합니다. 이는 실행 계획이며 통과 실적은 Actions 결과로 확인합니다. CSV 원본, 상태별 P50/P95/P99, 성공 건수, DB 정합성, 구간별 지표를 Actions artifact에 보존합니다.

- [설계·트랜잭션 경계·학생 락 판단](docs/design.md)
- [비교 조건·타임아웃 분석](docs/benchmark.md)
- 자동 검증: `.github/workflows/enrollment.yml`

이 프로젝트는 인증 없는 부하 실험용입니다. `studentId`는 신뢰된 테스트 입력이며 공개 서비스에서는 인증된 학생 ID로 대체해야 합니다. Actuator metrics도 운영에서는 내부망으로 제한해야 합니다.
