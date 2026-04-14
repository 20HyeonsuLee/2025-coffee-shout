# cs-troubleshoot Sprint Index

## Baseline 상태

- 단일 WAS + WAS 메모리(ConcurrentHashMap) 저장
- 로컬 개발: `docker-compose.yml` (MySQL) + `./gradlew bootRun`
- 이벤트 전파: Spring `ApplicationEventPublisher` + `@EventListener` Dispatcher
- Redis 라이브러리/yml 유지 (Sprint 3에서 재도입 예정, 현재 `@SpringBootApplication(exclude=Redis*AutoConfiguration)` 로 비활성)
- 백업: `archive/pre-cs-troubleshoot` 브랜치

## Sprint 로그

(스프린트 완료 시 이곳에 번호와 주제를 추가한다)
