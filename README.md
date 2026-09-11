# Class Enrollment

수강신청 동시성 문제를 단계적으로 재현하고 Redis Redisson 분산 락의 선택 근거를 검증하기 위한 프로젝트입니다.

## 현재 단계

- Redis를 적용하기 전 기본 수강신청 화면
- 학생 선택, 과목 검색 및 필터
- 수강신청과 취소
- 학점 제한과 시간 중복 검증
- 테스트 데이터 초기화

현재 버전의 데이터는 브라우저 메모리에서 동작합니다. 다음 단계에서 Spring Boot, MySQL 기반 API를 연결한 뒤 No Lock 동시성 문제를 재현합니다.

## 실행

`dist/index.html`을 정적 서버로 실행합니다.

```bash
python3 -m http.server 4173 --directory dist
```

브라우저에서 `http://localhost:4173`으로 접속합니다.

## 다음 단계

1. Spring Boot와 MySQL 기반 기본 수강신청 API
2. JMeter를 이용한 Race Condition 재현
3. DB 비관적 락 적용 및 병목 측정
4. Redis Redisson 과목별 분산 락 적용
5. Prometheus와 Grafana 기반 지표 비교
