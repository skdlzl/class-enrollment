# SET NX 비교 (E)

B는 기존 DB 락 구성입니다. C는 학생/과목 Redisson 락을 획득한 뒤 같은 DB 트랜잭션을 실행합니다. E는 같은 순서를 SET NX 락으로 실행합니다. 세 구성 모두 DB가 2개이며 DB의 학생/과목 락과 유일 제약을 유지합니다.

## 구현

학생 락 → 과목 락 → DB 트랜잭션 → 커밋/롤백 → 과목 락 해제 → 학생 락 해제.

E는 같은 Redisson 네트워크 클라이언트의 RBucket.setIfAbsent(token, Duration)을 사용합니다. Redis 명령은 SET key token NX PX입니다. UUID 소유자 토큰과 30초 TTL을 한 명령으로 저장합니다. SETNX 후 별도로 EXPIRE를 실행하지 않습니다. 해제는 Lua에서 토큰 확인과 DEL을 원자적으로 수행합니다.

실패한 획득은 20~40ms 지터를 두고 재시도합니다. 학생/과목 획득 대기 예산은 합쳐서 10초입니다. Redis 통신 시간까지 포함한 엄격한 HTTP 10초 제한은 아닙니다. HTTP 타임아웃과 결과 불명은 기존 조건과 동일하게 보고합니다.

E는 자동 연장이 없습니다. C는 watchdog 자동 연장이 있습니다. 따라서 C/E는 순수 명령 하나의 속도 비교가 아니라 Pub/Sub 기반 Redisson과 고정 임대/폴링 구현의 비교입니다. 만료 후 이전 요청과 새 요청이 겹쳐도 DB 안전장치가 최종 정합성을 보호합니다. 일반 RLock과 E 모두 도착 순서 보장을 주장하지 않습니다.

코스 Redis 락은 DB 연결 획득과 학생 검증까지 포함합니다. 이번 변경은 기존 C와 범위를 맞춘 비교이며 락 범위 축소 성능 개선이 아닙니다. AOP 전환도 수행하지 않습니다. 기존 Facade/Service 분리로 커밋 이후 해제 순서를 유지합니다.

## 검사와 실행

Docker Desktop이 켜져 있어야 실제 Redis/MySQL 통합 테스트가 실행됩니다.

```powershell
cd C:\portfolio\class-enrollment
.\backend\mvnw.cmd -f .\experiments\db-split\backend\pom.xml verify
```

테스트가 통과한 변경을 로컬 Git 커밋한 다음 기존 빌드 스크립트를 실행합니다. 원격 push는 필요 없습니다. 빌드 스크립트는 실험 코드가 커밋되지 않았으면 중단합니다.

```powershell
& .\experiments\db-split\scripts\build-a.ps1
& .\experiments\db-split\scripts\check-preflight.ps1
& .\experiments\db-split\scripts\start-comparison.ps1 -Mode E -Round 1
& .\experiments\db-split\scripts\run-comparison.ps1 -Jmeter $jmeter -Warmup
& .\experiments\db-split\scripts\run-comparison.ps1 -Jmeter $jmeter
& .\experiments\db-split\scripts\stop-comparison.ps1
```

`$jmeter`는 실제 jmeter.bat의 경로로 지정합니다. 기존 B/C 결과와 E 한 번을 먼저 진단합니다. 정합성이 깨지거나 응답 불명이 많으면 증량하지 않습니다. 성능 결론이 필요하면 같은 리비전/환경에서 B→C→E, C→E→B, E→B→C로 각 3회 측정합니다. 기존 자동 실행기의 모드 검증이 B/C/D에 고정돼 있다면 그대로 사용하지 않습니다.

완료율과 성공까지 P95를 함께 봅니다. 실패자를 제외한 P95만으로 승자를 정하지 않습니다. 신청용 조회 P95, 실제 RPS, 응답 불명, DB 연결 획득/점유 시간, Redis 대기/점유 시간, 정합성을 함께 보존합니다. 회차별 P95의 평균은 합쳐진 표본의 P95가 아닙니다.

## 추가 테스트

- SetNxFacadeTest: 두 락을 얻은 후 DB 실행, Redis 오류 시 DB 우회 금지, 인터럽트 보존.
- SetNxSafetyTest: 실제 Redis 대기 중 DB 연결 미점유, 이전 소유자의 해제가 새 소유자의 락을 삭제하지 않음. 기존 동시 정원/멱등성 및 DB 안전장치 테스트도 상속 실행.

환경에 Docker/PowerShell이 없으면 통합 테스트와 Windows 스크립트 실행을 완료했다고 주장할 수 없습니다.

## B/C/E 자동 3회 비교

저장소를 업데이트한 뒤 Docker Desktop을 켜고 다음 명령을 실행합니다. 서버는 직접 켜지 않습니다.

```powershell
& .\experiments\db-split\scripts\run-bce-rounds.ps1 -Jmeter 'C:\tools\apache-jmeter-5.6.3\bin\jmeter.bat'
```

기본 실행은 빌드/테스트 후 새 결과 묶음을 생성합니다. B-C-E / C-E-B / E-B-C 순서로 구성마다 예열 1회와 본 측정 1회를 실행합니다. 서버 종료와 30초 휴식도 자동입니다. 자동 절전은 방지하지만 수동 절전/덮개 닫기는 하지 않습니다.

완료하면 출력된 `BCE-3rounds-*.zip`을 전달합니다. ZIP에는 각 실행의 summary/conditions/database와 runs.csv 및 averages.csv가 있습니다. 원시 로그/JTL은 source-path.txt에 적힌 원본 폴더에 보존됩니다. 평균은 회차별 P95의 평균입니다.

완료된 실행을 건너뛰려면 같은 리비전에서 `-Resume -Bundle '<기존 묶음 경로>'`를 명시합니다. 중단된 실행의 started.json만 있으면 재실행으로 덮지 않고 멈춥니다. 정합성 오류는 중단하고 타임아웃/부하 미달은 실패 결과로 보존하며 고정 부하의 다음 구성으로 넘어갑니다.
