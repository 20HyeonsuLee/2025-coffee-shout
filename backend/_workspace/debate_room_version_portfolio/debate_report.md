# RoomVersion Portfolio Debate Report

## Execution Mode
- execution_mode: ISOLATED_MULTI_AGENT
- agents:
  - engineering-reviewer
  - performance-reviewer
  - test-reviewer / chaos architect
  - red-team-reviewer
  - temporary hiring portfolio reviewer
  - presentation-storyteller
- question: CoffeeShout Redis SSOT + Pub/Sub WebSocket 구조에서 분산락 이후 포트폴리오에 남길 핵심 설계와 부하테스트 지표

## 최종 결론
- 포트폴리오 주인공은 분산락이 아니다.
- 주인공은 Redis Pub/Sub의 at-most-once 특성을 전제로 한 **versioned snapshot recovery**다.
- 강한 메시지는 다음 한 문장이다.

> Redis SSOT + Pub/Sub 기반 WebSocket 전파 구조에서 분산락 이후에도 발생하는 메시지 유실, 중복, 순서 역전으로 인한 화면 상태 불일치를 재현하고, `roomVersion` 기반 stale drop / gap detection / full snapshot resync로 client-visible rollback을 방지했다. WAS 재시작과 reconnect storm을 포함한 장애 주입 부하테스트로 cross-WAS divergence window, recovery p95/p99, Pub/Sub propagation p99, Redis resync 비용을 측정했다.

## 합의된 설계 범위
### 반드시 넣을 것
- Redis가 방 상태의 SSOT
- Pub/Sub은 durable event log가 아니라 invalidation hint
- room 단위 `roomVersion`
- Pub/Sub envelope 필드
  - `version`
  - `eventId`
  - `originInstanceId`
  - `eventType`
  - `joinCode`
  - `publishedAt`
- 서버 subscriber의 stale drop
  - `version <= lastSeenVersion` drop
- gap detection
  - `version > lastSeenVersion + 1`이면 incremental apply 금지
  - Redis snapshot full sync
- 클라이언트 stale drop
  - `version <= currentVersion` drop
- reconnect / subscribe 초기화 / WAS restart 후 full snapshot sync
- 후속 이벤트 없는 유실을 위한 reconciliation trigger
  - reconnect refresh
  - subscribe 초기 snapshot
  - active room periodic version probe 중 하나 이상
- full sync herd 완화
  - room 단위 single-flight / request coalescing
  - jitter / backoff
  - resync cooldown

### 빼거나 보류할 것
- Actor model 구현
- Kafka / Redis Stream 도입
- Lua 기반 원자화
- ES / Mongo / CQRS 같은 범용 조회 전략 확장
- TPS / 평균 latency / 동시접속 수 단독 강조
- 클라이언트만 version 검증하는 구조

## Red-Team 반영 사항
### 후속 이벤트 없는 Pub/Sub 유실
- gap detection은 더 높은 version 이벤트가 뒤따를 때만 작동한다.
- 마지막 이벤트가 유실되고 후속 이벤트가 없으면 gap이 보이지 않는다.
- 포트폴리오에서 "gap detection으로 유실을 모두 잡았다"고 쓰면 안 된다.
- 방어 가능한 표현:
  - gap detection은 후속 이벤트가 있을 때 작동
  - 후속 이벤트 없는 유실은 reconnect / subscribe 초기 snapshot / periodic version probe로 bounded stale duration 문제로 낮춤

### version / state / publish 부분 실패
- Lua를 쓰지 않는다면 state 변경, version 갱신, publish가 완전 원자라고 주장하면 안 된다.
- Pub/Sub publish 실패는 push 누락이지 SSOT 손상은 아니다.
- 상태 정답은 Redis snapshot이고 Pub/Sub은 복구를 빠르게 하는 hint라고 설명한다.
- snapshot 자체에 `version`을 포함해야 한다.
- envelope version과 snapshot version이 어긋나면 stale broadcast 금지.

### full sync hot key
- gap / reconnect / WAS restart가 동시에 발생하면 full snapshot read가 Redis hot key를 만들 수 있다.
- 이때 coalescing은 캐시 성능 최적화가 아니라 복구 경로 보호 장치다.
- 측정 지표:
  - full sync amplification factor
  - Redis snapshot read peak
  - resync p99
  - client-visible stale duration p99

### snapshot + incremental 경합
- full snapshot을 읽는 동안 더 높은 version 이벤트가 올 수 있다.
- snapshot apply 전 version 비교가 필요하다.
- `snapshot.version < lastSeenVersion`이면 폐기 또는 재조회.

## 부하테스트 시나리오
| 순서 | 시나리오 | 목적 | 핵심 지표 |
|---|---|---|---|
| 1 | 정상 Pub/Sub baseline | 정상 전파 지연 측정 | propagation p95/p99 |
| 2 | duplicate event | 중복 drop 검증 | duplicate drop count, visible duplicate 0 |
| 3 | out-of-order event | stale drop 검증 | stale drop count, rollback 0 |
| 4 | gap event | full sync 복구 검증 | gap count, resync p95/p99 |
| 5 | silent Pub/Sub loss | 후속 이벤트 없는 유실 검증 | silent loss detection time |
| 6 | WAS restart | lastSeenVersion reset 방어 | restart recovery time |
| 7 | reconnect storm | 복구 경로 부하 검증 | latest version 도달 p99 |
| 8 | full sync herd | Redis hot key 완화 검증 | amplification factor, Redis OPS peak |
| 9 | snapshot failure | retry/backoff 검증 | resync failure count, bounded retry |
| 10 | snapshot 중 incremental event | 최신 상태 덮어쓰기 방지 | stale incremental applied 0 |

## 핵심 Metric
- `client.visible.rollback.count`
- `client.visible.stale.duration`
- `pubsub.message.stale.drop.count`
- `pubsub.message.duplicate.drop.count`
- `pubsub.message.gap.detected.count`
- `room.snapshot.resync.count`
- `room.snapshot.resync.latency`
- `room.snapshot.coalesced.count`
- `room.version.snapshot_mismatch.count`
- `cross_was.divergence.window`
- `was.restart.recovery.time`
- `redis.snapshot.read.count`
- `redis.ops.peak`
- `pubsub.propagation.latency`
- `full_sync.amplification.factor`

## 포트폴리오 구성
1. 문제 상황
   - 방 상태는 Redis에 있는데, 왜 클라이언트 화면은 서로 달라졌나
2. baseline
   - 방 단위 분산락으로 write invariant 보호
3. 남은 문제
   - Pub/Sub 중복 / 역순 / 유실 / WAS restart
4. 설계 원칙
   - Pub/Sub은 hint
   - Redis snapshot이 authoritative state
5. 구현 전략
   - `roomVersion`
   - stale drop
   - gap detection
   - full-state resync
6. Red-team 보강
   - silent loss
   - partial failure
   - full sync herd
   - snapshot race
7. 장애 주입 부하테스트
   - 정상군 / 장애 주입군 / 개선 후 비교
8. 측정 결과
   - stale duration
   - recovery p99
   - Redis OPS
   - full-sync cost
9. 대안 검토
   - Actor model
   - Redis Stream / Kafka
   - 클라이언트 단독 검증
10. 결론
   - 복잡한 패턴 도입보다 실패 모델에 맞는 복구 설계 선택

## 이력서 Bullet 후보
- Redis SSOT + Pub/Sub 기반 WebSocket 전파 구조에서 Pub/Sub을 durable event log가 아닌 invalidation hint로 제한하고, `roomVersion` 기반 stale drop / gap detection / full snapshot resync로 메시지 중복, 역순, 유실 상황의 client-visible stale duration과 recovery p99를 장애 주입 부하테스트로 계측했다.
- 분산락으로 방 단위 write invariant를 보호한 뒤, WebSocket 전파 계층의 ordering 문제를 versioned envelope로 분리해 서버 subscriber와 클라이언트 양쪽에서 stale event를 drop하도록 설계했다.
- WAS 재시작, reconnect storm, Pub/Sub 유실, out-of-order event를 주입해 Redis SSOT 대비 WAS / Client 관찰 상태의 lag, cross-WAS divergence window, full snapshot resync 비용을 측정했다.
- Actor model을 방 단위 command ordering 대안으로 검토했으나, 멀티 WAS owner routing과 failover 복잡도 대비 현재 요구사항에는 versioned snapshot recovery가 적합하다고 판단했다.

## 금지 표현
- Pub/Sub 메시지 순서 보장
- 메시지 유실 방지
- 강한 정합성 보장
- 완전한 장애 복구
- actor model보다 우수
- Redis로 분산 정합성 완전 해결
- 동시접속 N명 처리 단독 강조

## 최종 Arbiter 판정
- proceed
- 단, 포트폴리오 주장은 "보장"이 아니라 "실패를 전제로 한 감지 / 복구 / 측정"으로 제한한다.
- 구현 우선순위는 다음이 적절하다.
  1. envelope versioning
  2. subscriber stale drop / gap detection
  3. reconnect / subscribe snapshot sync
  4. silent loss reconciliation
  5. test delivery proxy로 duplicate / drop / delay / reorder 주입
  6. recovery metric 계측
  7. reconnect storm + coalescing 측정
