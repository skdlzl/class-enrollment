# 장애 대응 보완: failure-safety-v1

## 변경한 처리

### 학생 Redis 락 만료

동기 신청의 최종 쓰기 경로는 다음 순서를 공유한다.

1. 새 저장 트랜잭션에서 학생 DB 행을 먼저 잠근다.
2. 재학 상태와 중복 신청 및 현재 학점과 시간표를 다시 검사한다.
3. 과목 DB 행을 잠그고 최신 정원을 확인한다.
4. 신청 내역과 인원수를 같은 트랜잭션으로 저장한다.
5. 커밋 또는 롤백으로 DB 락을 해제한다. Facade가 Redis 락을 해제한다.

학생 DB 락을 얻기 전에는 해당 쓰기 트랜잭션의 일반 조회를 실행하지 않는다. 과목은 locking refresh로 최신 인원수를 가져온다. Redis 소유권을 한 번 확인하는 방식과 달리 검증부터 저장까지 DB에서 보호한다.

Redis는 요청 대기를 DB 밖에서 제어하고 DB는 최종 정합성을 보호한다. Redis 획득 실패 시 자동으로 우회하지 않고 기존처럼 503으로 거절한다.

### 커밋 후 응답 유실

`GET /api/enrollments/confirmation?studentId=1000&courseId=1`로 DB에 확정된 신청 ID를 확인한다.

- 200: 확정된 신청 ID를 반환한다.
- 404: 조회 시점에 확정 내역이 없다. 진행 중인 신청까지 실패했다고 단정하지 않는다.
- 동일 학생과 과목의 재신청: 기존 409 `DUPLICATE_ENROLLMENT` 계약을 유지한다. 확인 API로 기존 신청 ID를 얻는다.

학생 DB 락 안에서 중복을 다시 검사하며 DB의 `(student_id, course_id)` UNIQUE 제약도 유지한다. 취소 후 재신청 기능이 없는 현재 모델에 해당한다. 취소나 별도 요청 멱등 키는 이번 변경 범위가 아니다. 확인 API도 기존 프로토타입과 같은 studentId 입력 방식을 사용한다.

DB 커밋 응답을 확인하지 못한 트랜잭션 오류는 503 `ENROLLMENT_RESULT_UNKNOWN`으로 알린다. 자동으로 다시 저장하지 않는다. 락 대기 중 스레드가 중단되면 503 `LOCK_INTERRUPTED`를 반환하며 interrupt 상태를 유지한다.

### 락 해제 예외

소유권 확인과 unlock 사이에도 락이 만료될 수 있다. 이때 발생하는 `IllegalMonitorStateException`과 Redis 연결 오류가 이미 커밋된 성공 결과를 덮어쓰지 않도록 수정했다. 경고 로그를 남긴다.

### 서버 강제 종료 검증 코드

테스트 전용 자식 JVM이 실제 Spring 서비스와 MySQL 및 Redis를 사용한다. 운영 API에 장애 주입 기능을 추가하지 않았다.

- 커밋 전: INSERT/UPDATE flush 직후 자식 JVM을 멈춘다. 부모가 강제 종료한다. 신청과 인원수가 모두 0인지 확인한다.
- 커밋 후: 커밋 완료 직후 응답 전 자식 JVM을 멈추고 강제 종료한다. 신청과 인원수가 1로 유지되는지 확인한다.
- 두 경우 모두 finally가 실행되지 않았는지 확인한다. 학생 Redis 락이 만료되는지와 이후 요청 처리를 확인한다.

테스트 watchdog은 3초다. 실제 기본 설정은 30초이며 정상 실행 중 갱신된다. 락 획득 대기 제한 10초와는 다른 값이다. 이 테스트는 JVM 강제 종료를 다루며 DB 서버 장애나 네트워크 파티션 및 Redis failover 전체를 검증하지 않는다.

## 실제 실행 결과

2026-10-22 GitHub Actions에서 테스트 46개가 통과했다. 실패 0개와 오류 0개 및 생략 0개다. 단위 테스트 35개와 MySQL/Redis 통합 테스트 11개를 실행했다.

- 검증 코드 커밋: `37ec158f6bab7141525dc4f2ed6534bc461bd873`
- [실행 결과와 로그](https://github.com/skdlzl/class-enrollment/actions/runs/39712656495)
- MySQL 8.4와 Redis 7.4 테스트 컨테이너를 사용했다.
- 커밋 전후 자식 JVM 강제 종료 2개 시나리오를 통과했다.
- 학생 Redis 락 만료 후 학점 초과와 시간표 중복을 거절하는 2개 시나리오를 통과했다.
- 커밋 결과를 받지 못한 상황에서 확정 내역 조회와 재신청 중복 방지를 확인했다.
- 일반 예외 롤백과 DB 락 타임아웃 이후 복구 및 동시 정원 제한도 통과했다.

이 결과는 테스트에서 주입한 장애 범위에 해당한다. 실제 네트워크 파티션이나 Redis failover 및 DB 서버 자체 장애까지 검증한 것은 아니다.

## 실행 자동화

이 작업본의 변경 파일을 반영한 환경에서 Docker Desktop을 실행하고 다음 명령 하나로 검증한다. 기존 8080/8081 서버는 필요 없다. Testcontainers가 별도 MySQL/Redis를 생성하므로 기존 수강신청 데이터는 삭제하지 않는다.

```powershell
cd C:\portfolio\class-enrollment
.\scripts\verify-enrollment-failures.ps1
```

서버 종료 테스트는 학생/과목 테스트용 ID 1000/1을 격리된 컨테이너에서만 사용한다. 현재 사용 중인 서버 PID를 종료하지 않는다.

GitHub Actions `Enrollment failure safety`도 추가했다. 작업 브랜치 push와 workflow_dispatch 또는 관련 PR에서 실행하며 결과와 자식 JVM 로그를 아티팩트로 보존한다. CI 결과는 해당 커밋의 Actions 실행에서 확인한다.

## 성능 실험과 구분

이전 성능 실험은 학생 DB 재검증을 추가하기 전 코드의 결과다. 이번 변경 후 수치로 재사용하면 안 된다.

- PESSIMISTIC: 학생 Redis 진입 제어 + 학생/과목 DB 안전장치.
- REDIS: 학생/과목 Redis 진입 제어 + 학생/과목 DB 안전장치.
- DB_ONLY: 학생/과목 DB 안전장치.

REDIS 모드도 락 만료 때 정원과 학생 규칙을 보호하도록 보완했다. 이제 REDIS를 순수 Redis 락만 쓰는 구성이라고 표시하면 부정확하다. 기존 `enroll()`은 synchronized 비교 실험과 DB 락을 별도로 잡는 큐 처리 경로에서 유지된다. 과거 큐의 Redis 데이터 유실이나 failover는 이번 동기 신청 검증 대상에 포함하지 않는다.
