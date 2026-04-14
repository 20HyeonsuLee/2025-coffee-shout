# cs-troubleshoot Sprint Index

## Baseline 상태

- 단일 WAS + WAS 메모리(ConcurrentHashMap) 저장
- 로컬 개발: `docker compose up` (MySQL + app)
- 이벤트 전파: Spring `ApplicationEventPublisher` + `@EventListener` Dispatcher
- Redis 라이브러리/yml 유지 (Sprint 3에서 재도입 예정, 현재 `@SpringBootApplication(exclude=Redis*AutoConfiguration)` 로 비활성)
- 백업: `archive/pre-cs-troubleshoot` 브랜치
- 부하 테스트: `load-test/scenarios/` (artillery)

## 스프린트 상태 매트릭스

| Sprint | 주제 | 상태 | 산출물 |
|---|---|---|---|
| 1 | 스케일 아웃 → Sticky Session | **DOC_ONLY** | `sprint_1/01_architect_design.md`, `feedback.md` |
| 2 | Sticky Session 한계 → 폐기 | **DOC_ONLY** | `sprint_2/01_sticky_session_limits.md`, `feedback.md` |
| 3 | 브로커 선택 → Redis SSOT + Pub/Sub + Lua | 진행 예정 | — |
| 4 | 재접속 Thundering herd → Jitter + Coalescing | 대기 | — |
| 5 | Pub/Sub 메시지 유실 → 시퀀스 + 풀 싱크 | 대기 | — |
| 6 | 순서 역전 → 방 단위 액터 모델 | 대기 | — |
| 7 | 분산 스케줄링 → Sorted Set + 폴링 | 대기 | — |
| 8 | 중복 요청 → 멱등성 키 (Lua) | 대기 | — |
| 9 | 무중단 배포 검증 | 대기 | — |

### DOC_ONLY 결정 배경
Sprint 1-2는 "WAS 메모리 상태 저장의 분산 환경 실패"라는 업계 상식 안티패턴의 구현적 재현. 재현 인프라 구축 비용 대비 학습 가치가 낮다고 판단해 설계 문서로만 논증하고 skip. Sprint 3부터 실제 구현 진입. 각 스프린트 `feedback.md` 참고.

## 인사이트 문서

| 파일 | 내용 |
|---|---|
| `insights/01_why_redis_ssot.md` | Stream/MySQL/Redis 대안 비교, SSOT 부재가 시나리오 9개의 공통 원인이라는 메타 관찰, 자주 나오는 오해 교정 (OOM/리밸런싱) |
| `insights/02_actual_problems_in_project.md` | 이 프로젝트에서 실제 발생했던 SSOT 부재 문제 12개 사례 (파일/커밋 증거 기반). Sprint 3 motivation 원천 |

## 다음 단계 (Sprint 3 진입 조건)

Sprint 3 architect 에이전트가 `_workspace/sprint_3/01_architect_design.md` 작성 시 다음 세 문서를 motivation 근거로 인용:
1. `insights/01_why_redis_ssot.md` — 선택의 논리
2. `insights/02_actual_problems_in_project.md` — 프로젝트 실증
3. `sprint_2/01_sticky_session_limits.md` — 라우팅 층 해결책의 한계 확정

설계 완료 후 사용자 승인 게이트 → Phase 2 구현으로 진입.
