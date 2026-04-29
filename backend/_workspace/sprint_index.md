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
| 3 | Redis SSOT + Pub/Sub + roomVersion | **IMPLEMENTED_EVIDENCE** | `mission_room_version_consistency/implementation_report.md`, `portfolio_report.ko.md`, `load_test_repomap.md` |
| 4 | Snapshot resync herd guard | **UNIT_VERIFIED** | `sprint_4/01_architect_design.md`, `02_implementation_report.md`, `03_metric_capture_plan.md`, `feedback.md` |
| 5 | Pub/Sub silent loss / reconnect freshness | 대기 | `mission_room_version_consistency/mission_contract.md` |
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

## 현재 구현 체크포인트

| Commit | 범위 | 요약 |
|---|---|---|
| `96b4fe7` | Sprint 3 | roomVersion 기반 subscriber stale/gap 방어, load-test evidence, Grafana dashboard |
| `156a11c` | Evidence | before/after Grafana baseline, 포트폴리오 검증 문서, mission contract checkpoint |
| `e545042` | Sprint 4 | snapshot resync single-flight/coalescing/cooldown/retry, herd metric, Grafana panel |

### Sprint 4 범위 조정

초기 Sprint 4 설계(`sprint_4/01_architect_design.md`)는 클라이언트 재접속 후 REST room-state 조회의 jitter/coalescing을 다뤘다. 실제 구현은 현재 코드 흐름에 맞춰 **서버 Pub/Sub gap recovery의 Redis snapshot read herd 완화**로 좁혔다.

이 결정의 이유:

- `roomVersion` 도입 후 가장 가까운 hot path가 subscriber gap recovery였다.
- 클라이언트 stale drop/reconnect freshness는 아직 미구현이라 클라 중심 claim을 만들면 과장된다.
- 서버 안에서 coalescing/cooldown/retry를 단위 테스트로 검증할 수 있고, Grafana metric으로 포트폴리오 evidence를 만들 수 있다.

현재 Sprint 4는 `UNIT_VERIFIED` 상태다. 멀티 WAS 부하테스트와 Grafana 재캡처가 끝나면 `EVIDENCE_READY`로 올린다.
