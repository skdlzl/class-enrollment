# Redis 검색 캐시로 반복 조회의 DB 작업 줄이기

## 변경 목적

동기 수강신청의 학생/과목 DB 안전장치는 유지한다. 반복되는 공개 과목 목록과 검색 결과를 Redis에 1초간 공유해 검색으로 인한 DB 연결 점유를 줄인다. 신청 성능까지 개선되는지는 실제 측정이 필요하다. 이 변경의 효과를 Redis **분산 락**의 성능으로 설명하지 않는다.

기본 설정은 OFF다. 권장 실행 조합은 `DB_ONLY`와 `-SearchCache`다. 기존 혼합 구성을 유지하며 캐시만 비교하려면 양쪽 모두 `PESSIMISTIC`을 사용한다.

## 동작과 한계

- 대상: `GET /api/courses`만. 기존 UI의 별도 options API에는 적용되지 않는다.
- 검색 키: 연도, 학기, 학과, 학년, 키워드, 커서, 페이지 크기. 입력 검증 후 키를 생성한다.
- 같은 키는 두 서버가 Redis 결과를 공유한다. 캐시 적중 시 DB 조회가 없다.
- 미스 시 JVM 내 128개 고정 stripe로 동일 키의 중복 DB 조회를 줄인다. 서버 간 캐시 재생성까지 독점하지 않으므로 각 서버에서 한 번씩 조회할 수 있다.
- TTL 1초는 DB 조회 시작 시각부터 계산한다. 느린 조회나 늦게 도착한 Redis SET이 오래된 결과를 새 결과처럼 연장하지 않도록 payload에도 만료 시각을 기록한다. 서버 시간 동기화가 필요하다.
- 목록의 신청 인원은 최대 약 1초 늦을 수 있다. 목록은 좌석 예약이 아니다. 신청 시 DB 락 안에서 최신 정원, 학점, 시간표를 다시 확인한다.
- 마감 직후 오래된 검색 결과로 제출한 요청은 정상적인 COURSE_FULL 응답을 받을 수 있다. 캐시가 신청 재시도 횟수를 늘릴 수도 있으므로 완료율을 함께 본다.
- 캐시 예외/손상/100ms 응답 제한 초과 시 해당 JVM은 5초간 캐시를 우회한다. Redis 락을 쓰는 구성에서는 이 우회가 신청 락 장애까지 해결하지 않는다.
- 읽기와 쓰기에 각각 응답 제한이 적용된다. 타임아웃은 원격 명령의 취소를 보장하지 않는다. 만료 payload 검사로 늦은 쓰기의 신선도 연장을 막는다.
- 공개 카탈로그 전용이다. 사용자별 접근 제한이나 개인별 결과를 넣으려면 캐시 키와 권한 검사를 다시 설계해야 한다.
- 다양한 키가 한 번씩 조회되는 부하는 적중률이 낮고 Redis 왕복 비용이 추가될 수 있다. Redis 메모리 한도/퇴거 정책은 운영 환경에 맞게 설정해야 한다.

## 실행

8080/8081의 이전 Java 서버만 종료한 뒤 PowerShell에서 실행한다. 기존 DB 데이터는 삭제하지 않는다. MySQL 및 Redis 컨테이너가 실행 중이어야 한다.

```powershell
cd C:\portfolio\class-enrollment
git switch feat/redis-ingress-queue
git pull --ff-only
cd backend
.\mvnw.cmd "-Dtest=CourseSearchCacheTest,CourseSearchCacheIntegrationTest,CourseQueryControllerTest,EnrollmentWriteSafetyTest,EnrollmentServiceTest" clean package
if ($LASTEXITCODE -ne 0) { throw 'Build or safety tests failed' }
cd ..
powershell -ExecutionPolicy Bypass -File .\scripts\start-student-contention-servers.ps1 DB_ONLY -SearchCache
```

두 서버의 Started 로그 확인 후 선택적으로 성능을 측정한다. 새 부하 실험을 하지 않으면 성능 개선 수치는 확정하지 않는다.

```powershell
node .\scripts\run-enrollment-journey.mjs DB_ONLY --search --read-rps 50 --search-cache --warmup
node .\scripts\run-enrollment-journey.mjs DB_ONLY --search --read-rps 50 --search-cache
```

동일 JAR의 OFF 비교는 서버 시작의 `-SearchCache`와 실험 명령의 `--search-cache`를 둘 다 뺀다. Redis 락 효과와 캐시 효과를 구분하려면 락 구성은 양쪽 동일하게 유지한다. 과거 서로 다른 구현의 수치와 직접 비교하지 않는다.

## 확인할 결과

`summary.json`의 `searchCache`는 본 측정 전후 차이다. `hits / requests`가 적중률이고 `dbLoads`는 실제 검색 DB 호출 수다. 단순 Redis 접근 횟수와 혼동하지 않는다. `errors`와 `bypasses`는 Redis 장애/비활성 상태를 확인하는 수치다.

조회 P95, 신청 완료 시간 P95, 신청 완료율, 타임아웃, 조회 전송 생략, DB 풀 대기까지 함께 본다. 조회만 빨라지고 신청 완료율이 떨어지면 전체 성능 개선으로 결론 내리지 않는다.

단위 테스트는 캐시 적중 시 DB 호출 생략, 두 캐시 인스턴스 공유, 만료 payload, Redis 오류/무응답/쓰기 실패, DB 실패, 키 분리와 입력 검증을 확인한다. Testcontainers 통합 테스트는 실제 Redis 공유 및 오래된 조회 결과로 신청해도 정원 초과가 발생하지 않는지 확인한다.
