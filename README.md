> **학점교류 시나리오 추가:** 상대 대학 좌석을 예약하고 로컬 수강신청을 저장한 뒤 외부 예약을 확정합니다. 타임아웃은 처리 중으로 유지하고 복구기가 조회/확정/취소를 재시도합니다. [시나리오와 API 및 한계](docs/exchange.md)를 확인하세요. 새 예약 경로는 **12,000건 최종 정합성 검증과 테스트 39개를 통과**했습니다. 최초 처리 중 486건도 최종 상태로 복구했습니다. 아래 락 비교 수치는 기존 읽기 전용 자격 조회 실험 결과이며 새 예약 경로의 성능 수치가 아닙니다.

# Class Enrollment — concurrency rebuild

외부 학사 검증 API를 호출하는 수강신청 서비스입니다. **Redis 과목 락으로 외부 호출부터 DB 커밋까지 조율**하고, DB에는 정합성을 지키는 짧은 CAS 갱신을 둡니다. 학생 Redis 락은 사용하지 않습니다.

**Redis를 기본 전략으로 채택했습니다.** 이유는 집중 부하에서의 실패 억제, 외부 API 재호출 및 DB 커넥션 대기 감소입니다. 최종 Redis 72,000건에서 503은 0건이었습니다. 성공 응답 P95가 세 방식 중 가장 낮았다는 결과는 아니며, 수치와 한계를 [실측 결과](docs/results.md)에 공개합니다.

| 최종 집중 부하: 전략별 36,000건 | 비관적 | 낙관적 | Redis |
|---|---:|---:|---:|
| 신청 확정 | 300 | 300 | 300 |
| 503 | 306 | 1,105 | 0 |
| 외부 API 호출 | 300 | 11,360 | 300 |
| 성공 P95(ms) | 2,646.4 | 2,767.3 | 3,338.0 |

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

부하 실행 전 compose의 api1/api2는 중지하세요. 스크립트가 동일 JAR로 두 JVM을 시작합니다. 세 전략 × 두 부하 유형 × 세 회차 = **216,000 HTTP 요청의 정합성 검증을 완료**했고, 기능·장애 테스트 27개도 통과했습니다. 이 중 Redis는 72,000건이며 503은 0건입니다. 전체 요청이 신청 성공이라는 뜻은 아니며 정원 마감 409와 DB 전략의 503도 원본에 포함합니다. CSV 원본, 상태별 P50/P95/P99, 성공 건수, DB 정합성, 구간별 지표를 Actions artifact에 보존합니다.

- [실측 결과·전후 비교·원본](docs/results.md)
- [설계·트랜잭션 경계·학생 락 판단](docs/design.md)
- [비교 조건·타임아웃 분석](docs/benchmark.md)
- 자동 검증: `.github/workflows/enrollment.yml`

이 프로젝트는 인증 없는 부하 실험용입니다. `studentId`는 신뢰된 테스트 입력이며 공개 서비스에서는 인증된 학생 ID로 대체해야 합니다. Actuator metrics도 운영에서는 내부망으로 제한해야 합니다.
