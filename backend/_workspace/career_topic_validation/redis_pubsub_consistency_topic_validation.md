# Redis Pub/Sub Consistency Career Topic Validation

## Verdict

- verdict: PROCEED_WITH_SCOPE_CONTROL
- senior_signal: STRONG_IF_VERSIONED_RECOVERY_AND_METRICS_EXIST
- recruiter_signal: MEDIUM_NOW_STRONG_WITH_NUMERIC_RESULT
- domain_signal: STRONG_FOR_REALTIME_BACKEND_IF_FAILURE_MODEL_IS_EXPLICIT
- current_status: BASELINE_IMPLEMENTED_TOPIC_NOT_YET_COMPLETE

## One-Line Positioning

Redis SSOT + Pub/Sub 기반 WebSocket 전파 구조에서 방 단위 분산락으로 write invariant를 보호한 뒤, Pub/Sub의 중복/역순/유실을 전제로 `roomVersion` 기반 stale drop, gap detection, full snapshot resync를 설계하고 장애 주입 테스트와 Grafana metric으로 복구 시간을 계측한 프로젝트.

## Evidence Already Present

### Baseline concurrency evidence

- `DistributedLockConcurrencyTest`는 joinCode 200개 동시 claim에서 1개만 성공하는 invariant를 검증한다.
- 정원 초과 동시 입장, 중복 이름 동시 입장, 같은 플레이어 동시 제거 race를 Testcontainers Redis 기반으로 검증한다.
- `RoomService`에는 `enterRoom`, `changePlayerReadyState`, `updateMiniGames`, `removePlayer`에 room 단위 `@DistributedLock`이 적용되어 있다.
- `DistributedLockAspect`는 Redisson `tryLock`과 watchdog 기본 lease를 사용하고, acquire/hold observation을 기록한다.

### Pub/Sub baseline evidence

- 현재 Pub/Sub envelope는 `eventType`, `joinCode`, `payloadJson`, `publishedAt`, `originInstanceId`만 가진다.
- 현재 subscriber는 자기 메시지 skip과 propagation delay 기록 후 Redis snapshot을 읽어 local WebSocket broadcast를 수행한다.
- 즉, 현재 구현은 Pub/Sub 중복/역순/유실에 대한 version 기반 drop/gap/resync는 아직 없다.

### Prior design evidence

- 다자 토론 산출물은 포트폴리오 주인공을 분산락이 아니라 versioned snapshot recovery로 결론냈다.
- 합의된 핵심 설계는 `roomVersion`, envelope `version/eventId`, stale drop, gap detection, full snapshot resync, reconnect/silent loss 보완, full sync herd 완화다.
- 금지 표현도 이미 정의됐다: Pub/Sub 순서 보장, 메시지 유실 방지, 강한 정합성 보장 등은 쓰면 안 된다.

## Senior Hiring Evaluator

### Strong parts

- 단순 CRUD나 캐시 적용이 아니라, 실시간 상태 전파에서 생기는 failure model을 다룬다.
- Redis Pub/Sub을 durable log가 아니라 invalidation hint로 제한하는 표현은 실무적으로 성숙하다.
- 분산락과 versioned recovery를 분리하면 "write serialization"과 "read/broadcast consistency"의 책임 경계가 보인다.
- 장애 주입 테스트까지 붙이면 면접에서 깊게 설명할 수 있다.

### Weak parts now

- 현재 코드만 보면 아직 versioned recovery가 구현되지 않았다.
- 현재 수치 evidence가 없다. 동시성 테스트는 pass/fail invariant 검증이지 성능/복구 지표가 아니다.
- "분산락으로 해결"이라고 말하면 흔한 토픽이 된다.
- Pub/Sub 유실을 모두 잡는다고 말하면 반박당한다.

### Senior verdict

지금 상태로는 MEDIUM_SIGNAL. `roomVersion` 구현, 장애 주입, recovery p95/p99, stale duration, Redis resync 비용까지 수집하면 STRONG_SIGNAL.

## Recruiter Screening Reviewer

### 읽히는 강점

- 키워드가 좋다: Redis, Pub/Sub, WebSocket, distributed lock, concurrency, observability, Grafana.
- "동시성 문제를 재현하고 해결"은 서류에서 이해가 쉽다.
- 수치가 붙으면 bullet이 강해진다.

### 위험

- 너무 길고 복잡하게 쓰면 HR 단계에서 무슨 성과인지 안 읽힌다.
- "정합성", "versioned snapshot recovery"만 앞세우면 문제/결과가 흐려질 수 있다.
- 채용 공고가 일반 백엔드라면 Kafka/Redis Stream 같은 대형 인프라를 안 쓴 이유를 짧게 설명해야 한다.

### HR-friendly claim shape

좋은 bullet 구조:

> Redis Pub/Sub 기반 실시간 방 상태 전파에서 메시지 중복/역순/유실로 발생하는 화면 불일치를 재현하고, roomVersion 기반 복구 로직과 장애 주입 테스트를 도입해 stale 상태 지속 시간 p99를 N ms 이하로 제한.

아직 숫자가 없으면 `N ms`를 쓰지 말고 `계측 가능한 구조를 설계`까지만 말한다.

## Domain Expert Developer Reviewer

### Target domain fit

- 실시간 게임/협업/채팅/라이브 상태 공유 도메인에 잘 맞는다.
- 도메인 invariant는 "같은 방을 보는 사용자들이 최종적으로 같은 상태를 관찰해야 한다"다.
- Pub/Sub 유실/역순/중복, WAS restart, reconnect storm은 실제 운영 failure mode로 자연스럽다.

### Required domain invariants

- 한 방의 player list는 Redis snapshot 기준으로 authoritative해야 한다.
- stale event로 UI가 이전 상태로 rollback되면 안 된다.
- gap 발생 시 incremental apply 대신 full snapshot을 사용해야 한다.
- reconnect/subscribe 시 초기 snapshot으로 bounded stale duration을 가져야 한다.
- full snapshot resync가 동시에 몰려 Redis hot key를 만들지 않아야 한다.

### Domain verdict

실시간 상태 동기화 도메인 프로젝트로 적합하다. 다만 actor model을 구현하지 않았다는 점은 약점이 아니라, owner routing/failover 복잡도 때문에 보류했다는 의사결정으로 설명해야 한다.

## Evidence Gap

| Gap | Why It Matters | Required Artifact |
|---|---|---|
| versioned envelope 미구현 | 주인공 설계가 아직 코드에 없음 | `PubSubEnvelope`에 `version/eventId`, Redis room version 저장 |
| stale drop/gap detection 없음 | Pub/Sub failure model 대응 증거 부족 | subscriber lastSeenVersion test |
| reconnect/silent loss 보완 없음 | gap detection 한계 반박 대응 필요 | subscribe snapshot 또는 periodic probe |
| 장애 주입 테스트 없음 | 중복/역순/유실을 직접 재현했다는 근거 부족 | delivery proxy/test publisher |
| metric 없음 | 이력서 수치 claim 불가 | Prometheus/Grafana panel + raw result |
| Grafana screenshot 없음 | 포트폴리오 시각 evidence 부족 | `metric-evidence-capture` output |
| architecture diagram 없음 | 독자가 구조를 빠르게 이해하기 어려움 | Mermaid/C4 diagram |

## Metrics Required Before Resume Claim

Minimum viable metrics:

- `pubsub.propagation.latency.p95/p99`
- `pubsub.message.stale.drop.count`
- `pubsub.message.duplicate.drop.count`
- `pubsub.message.gap.detected.count`
- `room.snapshot.resync.latency.p95/p99`
- `client.visible.rollback.count`
- `client.visible.stale.duration.p99`
- `redis.snapshot.read.count`
- `full_sync.amplification.factor`

Nice-to-have:

- `distributed.lock.acquire.latency.p95/p99`
- `distributed.lock.hold.duration.p95/p99`
- `was.restart.recovery.time`
- `reconnect.storm.latest_version_reached.p99`
- `room.version.snapshot_mismatch.count`

## Topic Ranking

1. Versioned snapshot recovery for Redis Pub/Sub WebSocket state: 가장 강함.
2. Distributed lock for room-level write invariant: 보조 토픽으로 좋음.
3. Lua 제거와 Redisson lock 선택: 의사결정 소재로만 사용.
4. Actor model 검토 후 보류: 대안 비교 소재로 좋음.
5. 단순 동시 접속/평균 latency 수치: 단독으로는 약함.

## Recommended Portfolio Narrative

1. Problem
   - Redis가 SSOT인데도 멀티 WAS + Pub/Sub 전파에서 클라이언트 화면이 어긋날 수 있다.
2. Baseline
   - 방 단위 분산락으로 write invariant를 먼저 보호했다.
3. Remaining failure model
   - Pub/Sub은 at-most-once라 중복/역순/유실/restart/reconnect 문제가 남는다.
4. Design
   - Pub/Sub을 hint로 제한하고 Redis snapshot을 authoritative state로 둔다.
   - `roomVersion`으로 stale drop/gap detection/full snapshot resync를 수행한다.
5. Validation
   - duplicate/reorder/drop/reconnect storm/WAS restart를 주입한다.
   - p95/p99 recovery, stale duration, Redis resync cost를 측정한다.
6. Decision
   - Actor model, Redis Stream/Kafka, client-only validation을 비교하고 현재 요구사항에는 versioned snapshot recovery가 적합하다고 판단했다.

## Safe Resume Claim Before Metrics

아직 수치가 없을 때:

> Redis SSOT + Pub/Sub 기반 실시간 방 상태 전파에서 분산락으로 room write invariant를 보호하고, Pub/Sub의 중복/역순/유실 한계를 보완하기 위해 `roomVersion` 기반 stale drop/gap detection/full snapshot resync 설계를 수립했다.

## Strong Resume Claim After Metrics

수치와 Grafana evidence가 생긴 뒤:

> Redis SSOT + Pub/Sub 기반 WebSocket 전파 구조에서 메시지 중복/역순/유실을 장애 주입 테스트로 재현하고, `roomVersion` 기반 stale drop/gap detection/full snapshot resync를 도입해 client-visible rollback 0건, recovery p99 <N ms>, full sync amplification <N>x를 Grafana 지표로 검증했다.

## Final Decision

이 토픽은 진행 가치가 있다. 단, 제목은 "분산락으로 동시성 해결"이 아니라 "Redis Pub/Sub 실시간 상태 전파의 실패 모델을 재현하고 versioned snapshot recovery로 복구 가능하게 만든 프로젝트"가 되어야 한다.

Proceed 조건:

1. `roomVersion` 구현.
2. Pub/Sub duplicate/reorder/drop/gap 주입 테스트.
3. reconnect/silent loss 보완.
4. metric instrumentation.
5. Grafana screenshot evidence.
6. 포트폴리오 case study와 이력서 bullet은 수치가 나온 뒤 최종 작성.
