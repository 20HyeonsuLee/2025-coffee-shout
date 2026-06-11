---
name: cs-troubleshoot
description: "CoffeeShout 분산 시스템 트러블슈팅 하네스. 9개 시나리오를 스프린트로 진행하며 분산 시스템 문제를 직접 재현하고 해결한다. '시나리오 N 시작', '트러블슈팅 진행', '다음 시나리오', '분산 시스템 학습', '커피쏴 트러블슈팅' 요청 시 사용."
---

# cs-troubleshoot — 분산 시스템 트러블슈팅 하네스

## 핵심 철학

- 시나리오별로 분산 시스템 문제를 **직접 재현**하고, 원인을 분석하고, 해결하며 아키텍처를 성장시킨다.
- 각 시나리오는 "요구사항 → 구현 → 문제 발생 → 원인 분석 → 해결 → **벤치마크**" 흐름을 따른다.
- 이력서·인터뷰 활용을 전제로 **설계 의사결정의 근거**와 **정량 측정**을 산출한다.
- 이전 시나리오의 해결이 다음 시나리오의 출발점이 된다.

## 시나리오 매핑

| Sprint | 시나리오 | 핵심 문제 | 해결 방향 | 우선순위 |
|---|---|---|---|---|
| 1 | 스케일 아웃 | 다른 WAS 유저에게 이벤트 안 감 | Sticky Session (임시) | — |
| 2 | 서버 장애 | Sticky Session에서 방 전멸 | Sticky Session 폐기 | — |
| 3 | 브로커 선택 | 이벤트 전파 기술 선택 | Redis SSOT + Pub/Sub + Lua | **★** |
| 4 | 재접속 | Thundering herd | Jitter + Request Coalescing | **★** |
| 5 | 메시지 유실 | Pub/Sub fire-and-forget | 시퀀스 번호 + 풀 스테이트 싱크 | **★** |
| 6 | 순서 역전 | 멀티스레드 처리 순서 꼬임 | 방 단위 액터 모델 | — |
| 7 | 분산 스케줄링 | 타이머 소멸/중복/유실 | Sorted Set + 폴링 | — |
| 8 | 중복 요청 | 재전송으로 이중 처리 | 멱등성 키 (Lua) | **★** |
| 9 | 배포 | 무중단 배포 필요 | 기존 아키텍처 검증 | — |

사용자가 "시나리오 N 시작"을 요청하면 Sprint N을 시작한다.
"다음 시나리오"를 요청하면 `_workspace/sprint_*` 중 최대 번호 + 1을 시작한다.

## 이력서 중점 4스프린트

★ 표시된 S3/S4/S5/S8은 **설계 의사결정 서술 + Before/After 정량 비교**를 필수 산출물로 가진다. 나머지 스프린트는 축소 산출물(재현·해결만)로 진행 가능.

### S3: Redis SSOT + Pub/Sub + Lua

| 구분 | 내용 |
|---|---|
| 의사결정 영역 | 메시징 기술(Kafka vs RabbitMQ vs Redis Pub/Sub) · 원자성(Redisson Lock vs MULTI-EXEC vs Lua) · 채널 전략(도메인 분할 vs 단일 vs 방별) · envelope 구조(다형 vs flat record) · 자기 메시지 필터 · 테스트 격리(@Profile) |
| 측정 지표 | joinCode 동시 생성 N=200 → 중복 발생률 · 정원 8 방 동시 입장 N=20 → 9명+ 초과 건수 · 중복 이름 동시 입장 → 허용 건수 · Ready/Remove 레이스 → 유령 HSET 건수 · idle 상태 Redis OPS(피드백 루프 유무) |
| Before/After | **Non-Lua 경로**(여러 명령 분리) vs **Lua 경로**(원자 스크립트). 같은 Redis 위에서 원자성 유무만 변수로 둠 |

### S4: Thundering Herd — Jitter + Request Coalescing

| 구분 | 내용 |
|---|---|
| 의사결정 영역 | 재시도 전략(backoff 형태) · Jitter 위치(클라이언트 vs 서버) · Coalescing 범위(전역 vs 방별 vs 요청별) · 캐시 TTL(50/200ms/무한) · Invalidation(Pub/Sub vs TTL vs 수동) |
| 측정 지표 | 동시 재접속 N=100 → Redis OPS 피크 · p50/p95/p99 latency · timeout 발생 건수 · WAS CPU 스파이크 · Coalescing hit ratio |
| Before/After | **Jitter 없음 / Coalescing 없음** vs **Jitter 적용 / Coalescing 적용**. 같은 재접속 부하 프로파일에 각각 측정 |

### S5: 메시지 유실 — 시퀀스 번호 + 풀 스테이트 싱크

| 구분 | 내용 |
|---|---|
| 의사결정 영역 | Pub/Sub 유지 vs Streams/Kafka 전환 · seq 발급 위치(클라 vs 서버 vs Lua) · fullstate 저장(Hash vs snapshot) · 재동기 트리거(gap 탐지 vs 주기적 vs 요청 시) · 순서 보장 범위 |
| 측정 지표 | 인위적 Pub/Sub drop 10% 주입 → 클라이언트 state drift 비율 · gap 탐지 후 fullstate sync recovery time · 정상 상태 latency overhead · Pub/Sub payload 크기 증가량 |
| Before/After | **seq/fullstate 없음** (유실 감지 불가) vs **seq/fullstate 있음** (자동 복구). drop 비율별로 측정 |

### S8: 멱등성 — Lua check-and-mark

| 구분 | 내용 |
|---|---|
| 의사결정 영역 | idempotency key 위치(헤더 vs body vs 자동) · 마킹 저장소(Set vs String TTL vs Hash) · TTL 기간 · 충돌 시 동작(기존 응답 재생 vs 409 vs 무시) · Lua 원자화 범위 |
| 측정 지표 | 동일 eventId 동시 N=50 요청 → 실제 처리된 작업 건수(목표 1) · 10초 간격 재시도 → TTL 동작 · 정상 상태 Lua overhead(추가 RTT 없음) |
| Before/After | **멱등성 키 없음**(동시 N=50 → N건 처리) vs **Lua check-and-mark**(1건 처리, N-1건 skip). 같은 요청 파이프라인으로 측정 |

## 스프린트 관리

```
_workspace/
├─ sprint_1/
│  ├─ 01_architect_design.md
│  ├─ reproduction_log.md
│  ├─ benchmark.md          ← ★ 스프린트만 작성
│  ├─ eval_script.md
│  ├─ eval_quality.md
│  └─ feedback.md
├─ sprint_2/
│  └─ ...
└─ sprint_index.md
```

스프린트 번호 = `_workspace/sprint_*` 중 최대 번호 + 1.

> 작업 기준 디렉토리(CWD)는 `backend/`이다. 본 스킬 내 모든 상대 경로는 backend 디렉토리를 기준으로 한다.

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
- ★ 스프린트(S3/S4/S5/S8)의 경우 **설계 의사결정 매트릭스**와 **비교 실험 설계** 섹션을 반드시 포함

설계 결과를 사용자에게 제시하고 승인을 받는다. 승인 없이 Phase 2로 넘어가지 않는다.

### Phase 2 — 구현 (자율, 4단계)

`cs-troubleshoot-developer` 에이전트.

```
Agent(subagent_type="general-purpose", model="sonnet")
```

프롬프트:
- `.claude/agents/cs-troubleshoot-developer.md`를 읽고 역할 수행
- `_workspace/sprint_N/01_architect_design.md` 읽기
- 4단계 구현:
  - **Step A**: 인프라 구성 (Docker Compose, Nginx, artillery/k6 스크립트)
  - **Step B**: 문제 재현 코드 + 재현 로그 (`reproduction_log.md`)
  - **Step C**: 해결 구현 + 동일 부하 재검증
  - **Step D** (★ 스프린트만): 비교 실험 실행 + `benchmark.md` 작성
- 구현 후 `./gradlew compileJava`로 검증

### Phase 3 — 검증 (자율, 순차)

eval-script → PASS일 때만 eval-quality.

**Step 1: eval-script** (haiku)
- `.claude/agents/cs-troubleshoot-eval-script.md`
- FAIL → Phase 2 복귀

**Step 2: eval-quality** (sonnet, eval-script PASS 후)
- `.claude/agents/cs-troubleshoot-eval-quality.md`
- ★ 스프린트에서 `benchmark.md` 누락 시 FAIL
- FAIL → Phase 2 복귀

### Phase 4 — Context Sync

`sync-project-context` 스킬 호출 → CLAUDE.md 갱신.

### Phase 5 — Feedback Write

스프린트에서 사용자와 상호작용하며 받은 피드백을 `_workspace/sprint_N/feedback.md`에 기록한다.
특히 시나리오 진행 중 발견한 예상 밖의 문제나 인사이트를 기록한다. ★ 스프린트의 피드백 루프 버그처럼 "수치로 드러난 사고"는 benchmark.md의 Before/After 근거로 링크한다.
