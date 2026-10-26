# 신청 확정 후 알림 발송 실험

신청/취소와 알림 outbox INSERT를 같은 DB B 트랜잭션에 기록합니다. 신청이 롤백되면 이벤트도 롤백됩니다. 외부 알림 HTTP 호출은 별도 워커가 커밋 이후 수행합니다. 기본 설정은 알림 OFF이며 기존 B/C/E 측정기는 알림이 켜져 있으면 중단합니다.

## 역할

- DB 유니크 `(request_id,event_type)`: 같은 이벤트의 중복 생성 방지.
- 짧은 DB 선점: PENDING 또는 lease가 만료된 PROCESSING만 원자적으로 가져옵니다.
- Redis 락: 같은 이벤트를 동시에 가져가려는 워커를 중재합니다. 만료 후 겹침 가능성은 남습니다.
- DB owner 토큰: 이전 워커의 뒤늦은 성공/실패 응답이 새 소유자의 상태를 덮지 못하게 합니다.
- 외부 공급자 Idempotency-Key: 응답 유실과 만료 후 재호출 시 실제 발송 중복을 방어합니다. 테스트 공급자는 같은 키/다른 본문을 거절합니다.

**DB 유니크만으로 외부 발송 중복을 막지는 못합니다.** 공급자가 멱등 키를 지원하지 않으면 정확히 한 번 발송을 보장하지 않습니다. 공급자의 멱등 키 보존 기간이 전체 재시도/복구 기간보다 길어야 합니다. 실제 공급자의 응답 규약과 결과 조회 API 연동은 별도입니다. 현재 어댑터는 200/201을 공급자 접수 확인으로 취급하며 수신자 전달 완료를 뜻하지 않습니다.

## 상태와 복구

PENDING → PROCESSING → SENT. 5xx/429/408/전송 결과 불명은 지수 지연 후 같은 키로 재시도합니다. 만료된 PROCESSING은 재선점합니다. 영구 실패나 최대 시도 횟수 초과는 REVIEW로 남겨 수동 대조합니다. REVIEW는 미발송 확정 상태가 아닙니다. 발송 후 DB ack 실패도 lease 만료 후 같은 키로 재시도합니다. 외부 장애가 신청 성공 응답을 롤백하지 않습니다.

기본 lease는 DB/Redis 각각 30초이며 HTTP 제한은 3초입니다. lease보다 호출이 오래 걸리는 테스트도 포함합니다. UUID owner 조건부 갱신은 외부 API를 fencing하지 않습니다. 외부 중복 방어는 공급자의 멱등 처리에 의존합니다.

CONFIRMED와 CANCELLED는 각각 당시의 불변 이벤트입니다. 현재 단순 워커는 두 종류의 발송 순서를 보장하지 않습니다. 실제 서비스에서 순서 보장이나 이미 취소된 신청의 확정 알림 억제가 필요하면 학생/신청별 순번과 공급자 상태 정책을 추가해야 합니다.

## 비교 세 구성

| 구성 | 외부 호출 중 DB 연결 점유 | 설명 |
|---|---|---|
| DB_INLINE | 있음 | 알림 행을 FOR UPDATE로 잡고 HTTP 호출. 느린 비교 기준이며 권장 운영 경로가 아닙니다. |
| DB_CLAIM | 없음 | 짧은 DB 선점 후 외부 호출. Redis 없이도 가능한 대안입니다. |
| REDIS_OUTBOX | 없음 | Redis 중재와 같은 DB 선점 후 외부 호출. |

DB_INLINE 대비 개선을 Redis 단독 효과라고 주장하지 않습니다. 트랜잭션 밖으로 호출을 이동한 효과와 Redis 추가 효과를 구분합니다. 서로 다른 메시지는 같은 Redis 키로 직렬화하지 않습니다.

## 실행

Docker Desktop만 켜면 됩니다. 기존 신청 서버를 켤 필요가 없습니다. Testcontainers가 임의 포트로 MySQL 2개와 Redis를 띄우며 로컬 HTTP 가짜 공급자를 사용합니다. 실제 알림은 발송하지 않습니다.

```powershell
cd C:\portfolio\class-enrollment
& .\experiments\db-split\scripts\run-notification-tests.ps1
```

같은 DB와 pool=2, worker threads=6, 공급자 지연=100ms 조건으로 각 구성 12건을 처리합니다. 구성별 1건 예열 뒤 세 구성의 순서를 바꿔 3회 측정합니다. 이 결과는 병목 확인용 소규모 알림 실험이며 수강신청 500명 실험이나 운영 서버 성능 수치와 합치지 않습니다. 컨테이너 CPU/메모리 예산은 기존 B/C/E 부하 실험과 같다고 가정하지 않습니다.

보고서: notification-comparison.json. 완료까지 P95, 배경 SELECT 1 지연, 실패 수, 공급자 요청/고유 접수 수, 연결 획득/점유 누적 지표를 기록합니다. metricsAfter에서 metricsBefore를 빼면 측정 구간 값을 얻습니다. 배경 조회는 closed-loop이며 실패를 포함한 P95를 명시합니다. 실행 시간이 짧아 지연의 정밀한 순위 판단에는 적합하지 않습니다.

## 검증 범위

- 신청 롤백 시 outbox도 없음. 멱등 재신청/취소 시 이벤트 각 1개.
- 외부 HTTP 처리 중 DB_CLAIM/REDIS_OUTBOX의 신청 풀 active=0, DB_INLINE active=1 확인.
- 두 워커의 동일 이벤트 경쟁.
- Redis와 DB lease 만료 후 외부 호출이 겹쳐도 공급자 멱등 처리로 고유 접수 1개.
- 외부 접수 후 응답 유실, ack 없는 워커 중단 상태에서 재처리.
- 이전 owner의 ack 거부. 일시 실패 재시도 및 영구 실패 REVIEW.

워커 중단은 ack 생략과 lease 만료로 재현합니다. OS 강제 종료/Redis failover/실제 알림톡 공급자 장애까지 검증한 것은 아닙니다.

## 수동으로 기능 활성화

별도 실험 서버에서 `experiment.notifications.enabled=true`, `experiment.notifications.auto=true`, `experiment.notifications.strategy=REDIS_OUTBOX`, `experiment.notifications.url=<테스트 공급자 /send URL>`, `experiment.redis.address=<Redis URL>`를 지정합니다. 실제 계정 연결 전 공급자 멱등 계약과 payload/응답 규약을 맞춰야 합니다. 기본 OFF로 유지해 기존 신청 실험 결과를 보존합니다.
