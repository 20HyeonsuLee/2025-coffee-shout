---
name: cs-troubleshoot
description: "CoffeeShout 분산 시스템 트러블슈팅 하네스. 9개 시나리오를 스프린트로 진행하며 분산 시스템 문제를 직접 재현하고 해결한다. '시나리오 N 시작', '트러블슈팅 진행', '다음 시나리오', '분산 시스템 학습', '커피쏴 트러블슈팅' 요청 시 사용."
---

# cs-troubleshoot — 분산 시스템 트러블슈팅 하네스

## 핵심 철학

- 시나리오별로 분산 시스템 문제를 **직접 재현**하고, 원인을 분석하고, 해결하며 아키텍처를 성장시킨다.
- 각 시나리오는 "요구사항 -> 구현 -> 문제 발생 -> 원인 분석 -> 해결 -> 검증" 흐름을 따른다.
- 이전 시나리오의 해결이 다음 시나리오의 출발점이 된다.

## 시나리오 매핑

| Sprint | 시나리오 | 핵심 문제 | 해결 방향 |
|--------|---------|----------|----------|
| 1 | 스케일 아웃 | 다른 WAS 유저에게 이벤트 안 감 | Sticky Session (임시) |
| 2 | 서버 장애 | Sticky Session에서 방 전멸 | Sticky Session 폐기 |
| 3 | 브로커 선택 | 이벤트 전파 기술 선택 | Redis SSOT + Pub/Sub + Lua |
| 4 | 재접속 | Thundering herd | Jitter + Request Coalescing |
| 5 | 메시지 유실 | Pub/Sub fire-and-forget | 시퀀스 번호 + 풀 스테이트 싱크 |
| 6 | 순서 역전 | 멀티스레드 처리 순서 꼬임 | 방 단위 액터 모델 |
| 7 | 분산 스케줄링 | 타이머 소멸/중복/유실 | Sorted Set + 폴링 |
| 8 | 중복 요청 | 재전송으로 이중 처리 | 멱등성 키 (Lua) |
| 9 | 배포 | 무중단 배포 필요 | 기존 아키텍처 검증 |

사용자가 "시나리오 N 시작"을 요청하면 Sprint N을 시작한다.
"다음 시나리오"를 요청하면 `_workspace/sprint_*` 중 최대 번호 + 1을 시작한다.

## 스프린트 관리

```
_workspace/
├─ sprint_1/
│  ├─ 01_architect_design.md
│  ├─ reproduction_log.md
│  ├─ eval_script.md
│  ├─ eval_quality.md
│  └─ feedback.md
├─ sprint_2/
│  └─ ...
└─ sprint_index.md
```

스프린트 번호 = `_workspace/sprint_*` 중 최대 번호 + 1.

> 작업 기준 디렉토리(CWD)는 `backend/`이다. 본 스킬 내 모든 상대 경로(`_workspace/`, `.claude/agents/`, `src/`, `scripts/`, `infra/`)는 backend 디렉토리를 기준으로 한다.

## 실행 절차

### Phase 1 — 실험 설계 (사용자 승인 게이트)

`cs-troubleshoot-architect` 에이전트를 호출한다.

```
Agent(subagent_type="general-purpose", model="opus")
```

프롬프트:
- `.claude/agents/cs-troubleshoot-architect.md`를 읽고 역할 수행
- 사용자가 요청한 시나리오 번호와 내용
- 이전 스프린트 설계 문서 참조 (있는 경우)
- 산출물: `_workspace/sprint_N/01_architect_design.md`

설계 결과를 사용자에게 제시하고 승인을 받는다. 승인 없이 Phase 2로 넘어가지 않는다.

### Phase 2 — 구현 (자율, 3단계)

`cs-troubleshoot-developer` 에이전트.

```
Agent(subagent_type="general-purpose", model="sonnet")
```

프롬프트:
- `.claude/agents/cs-troubleshoot-developer.md`를 읽고 역할 수행
- `_workspace/sprint_N/01_architect_design.md` 읽기
- 3단계 구현:
  - **Step A**: 인프라 구성 (Docker Compose, Nginx, k6 스크립트)
  - **Step B**: 문제 재현 코드 작성 + 재현 로그 수집 (`reproduction_log.md`)
  - **Step C**: 해결 구현 + 동일 부하 재검증
- 구현 후 `./gradlew compileJava`로 검증

### Phase 3 — 검증 (자율, 순차)

eval-script -> PASS일 때만 eval-quality.

**Step 1: eval-script** (haiku)
- `.claude/agents/cs-troubleshoot-eval-script.md`
- FAIL -> Phase 2 복귀

**Step 2: eval-quality** (sonnet, eval-script PASS 후)
- `.claude/agents/cs-troubleshoot-eval-quality.md`
- FAIL -> Phase 2 복귀

### Phase 4 — Context Sync

`sync-project-context` 스킬 호출 -> CLAUDE.md 갱신.

### Phase 5 — Feedback Write

스프린트에서 사용자와 상호작용하며 받은 피드백을 `_workspace/sprint_N/feedback.md`에 기록한다.
특히 시나리오 진행 중 발견한 예상 밖의 문제나 인사이트를 기록한다.
