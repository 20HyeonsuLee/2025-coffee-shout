# Sprint 4 Implementation Report: Snapshot Resync Herd Guard

작성일: 2026-04-29

## 결론

Sprint 4의 실제 구현 범위는 기존 설계 문서의 "클라이언트 재접속 REST 조회 coalescing" 전체가 아니라, `roomVersion` 기반 Pub/Sub gap recovery에서 발생할 수 있는 **Redis snapshot read herd**를 서버 안에서 완화하는 것으로 좁혔다.

이 변경은 포트폴리오에서 다음 문장으로 설명할 수 있다.

> Pub/Sub gap/reconnect/WAS restart 상황에서 모든 subscriber가 Redis snapshot을 동시에 읽어 hot key를 만들 수 있는 복구 경로 병목을 식별하고, 방 단위 single-flight/coalescing/cooldown/retry를 적용해 full-state recovery의 Redis read amplification을 계측 가능하게 낮췄다.

## 배경

Sprint 3 이후 서버 subscriber는 `roomVersion`으로 gap을 감지하면 Redis SSOT snapshot을 다시 읽고 WebSocket full-state broadcast를 수행한다. 이 구조는 stale 상태 복구에는 맞지만, 다음 상황에서 같은 방의 snapshot read가 몰릴 수 있다.

- 같은 방에서 여러 gap 이벤트가 짧은 시간에 도착
- WAS restart 후 subscriber `lastSeenVersion`이 초기화
- reconnect/subscribe 초기화가 동시에 발생
- Pub/Sub 지연으로 여러 이벤트가 한꺼번에 처리됨

따라서 Sprint 4는 "복구 로직 자체가 Redis hot key를 만들지 않게 한다"를 목표로 잡았다.

## 구현 범위

| 영역 | 파일 | 내용 |
|---|---|---|
| Gap recovery orchestration | `src/main/java/coffeeshout/global/messaging/SnapshotResyncCoordinator.java` | 방 단위 single-flight, coalescing, version-aware cooldown, retry/backoff/jitter |
| Subscriber integration | `src/main/java/coffeeshout/global/messaging/PubSubSubscriber.java` | gap 발생 시 직접 snapshot read 대신 coordinator를 통해 broadcast |
| Metric | `src/main/java/coffeeshout/global/metric/PubSubMetricService.java` | snapshot read, coalesced, cooldown skip, retry, failed metric 추가 |
| Unit test | `src/test/java/coffeeshout/global/messaging/SnapshotResyncCoordinatorTest.java` | 동시 coalescing, 더 높은 version 합류, cooldown, retry 검증 |
| Existing subscriber test | `src/test/java/coffeeshout/global/messaging/PubSubSubscriberVersionTest.java` | coordinator 주입 및 snapshot read metric 검증 |
| Grafana | `monitor/grafana/dashboards/room-version-consistency-dashboard.json` | Snapshot Read, Coalesced Resync, Cooldown Skip, Full Sync 증폭률 패널 추가 |
| Evidence docs | `_workspace/mission_room_version_consistency/*` | report, repomap, mission contract에 Sprint 4 구현 범위 반영 |

## 동작 설계

### 1. Single-flight

`SnapshotResyncCoordinator`는 `joinCode`를 key로 `inFlight` map을 둔다. 같은 방의 resync가 진행 중이면 새 요청은 loader를 다시 실행하지 않고 기존 `CompletableFuture` 결과에 합류한다.

효과:

- 같은 방 같은 version의 동시 gap 요청은 Redis snapshot read 1회로 합쳐진다.
- 추가 요청 수는 `room_snapshot_resync_coalesced_total`로 관측한다.

### 2. Coalescing 중 최신성 보호

단순 coalescing만 하면 `version=3` snapshot read 중 `version=4` 요청이 합류했을 때 최신 상태를 놓칠 수 있다. 그래서 in-flight 객체가 `requestedVersion`의 max 값을 유지하고, 첫 read가 끝난 뒤 더 높은 version이 들어왔으면 snapshot을 한 번 더 읽는다.

이 선택은 read 절약보다 상태 최신성을 우선한다.

### 3. Version-aware cooldown

최근 같은 방에서 `version >= requestedVersion`까지 복구했다면 짧은 cooldown 동안 추가 snapshot read를 생략한다.

기본값:

- cooldown: 250ms
- maxAttempts: 3
- backoff: 20ms
- maxJitter: 40ms

주의: 더 높은 version 요청은 cooldown 중에도 skip하지 않는다.

### 4. Jitter / Backoff / Retry

snapshot read 또는 broadcast 실패가 발생하면 제한된 횟수만 재시도한다. 재시도 전 backoff와 jitter를 섞어 WAS 여러 대가 같은 시각에 Redis를 다시 치지 않도록 한다.

## Metric

| Metric | 의미 | 포트폴리오 해석 |
|---|---|---|
| `room_snapshot_read_total` | 실제 Redis snapshot read 시도 횟수 | gap recovery가 Redis에 만든 실제 부하 |
| `room_snapshot_resync_total` | snapshot read 후 full-state broadcast 성공 횟수 | recovery path 성공량 |
| `room_snapshot_resync_duration_seconds` | snapshot read + broadcast 소요 시간 | recovery latency p95/p99 |
| `room_snapshot_resync_coalesced_total` | in-flight resync에 합류한 요청 수 | 추가 Redis read를 만들지 않은 요청 |
| `room_snapshot_resync_cooldown_skip_total` | 최근 복구 version으로 커버되어 skip한 요청 수 | cooldown으로 막은 중복 read |
| `room_snapshot_resync_retry_total` | 실패 후 재시도 횟수 | 장애 상황에서 retry가 실제 작동했는지 |
| `room_snapshot_resync_failed_total` | retry 후 최종 실패 | 복구 실패율 |
| `room_snapshot_read_total / pubsub_message_gap_detected_total` | full sync 증폭률 | 1보다 크면 recovery path가 Redis hot key 위험 |

## 검증

통과:

```bash
./gradlew test --tests coffeeshout.global.messaging.PubSubSubscriberVersionTest --tests coffeeshout.global.messaging.SnapshotResyncCoordinatorTest --no-configuration-cache
node -e "JSON.parse(require('fs').readFileSync('monitor/grafana/dashboards/room-version-consistency-dashboard.json','utf8'))"
git diff --check -- <Sprint 4 touched files>
```

결과:

- Messaging/resync unit tests: PASS
- Grafana dashboard JSON parse: PASS
- touched files whitespace check: PASS

블로커:

```bash
docker info --format '{{.ServerVersion}}'
```

현재 shell에서는 Docker daemon에 연결할 수 없다.

```text
Cannot connect to the Docker daemon at unix:///Users/leehyeonsu/.docker/run/docker.sock. Is the docker daemon running?
```

따라서 Testcontainers 기반 `DistributedLockConcurrencyTest`와 멀티 WAS 부하테스트/Grafana 최신 캡처는 이번 문서화 시점에는 실행하지 못했다.

## 커밋

| Commit | 내용 |
|---|---|
| `156a11c` | before/after Grafana evidence, career validation, debate report, mission contract checkpoint |
| `e545042` | snapshot resync single-flight/coalescing/cooldown/retry 구현 |

## 안전한 주장

- Redis Pub/Sub gap recovery에서 Redis snapshot read가 몰리는 위험을 별도 문제로 분리했다.
- 같은 방의 동시 resync 요청을 single-flight/coalescing으로 묶었다.
- 더 높은 version 요청은 cooldown/coalescing 때문에 누락되지 않도록 설계했다.
- snapshot read, coalescing, cooldown skip, retry/fail을 Prometheus/Grafana에서 관측 가능하게 만들었다.

## 아직 주장하면 안 되는 것

- production 규모에서 Redis hot key 문제가 해결됐다.
- reconnect storm 수치가 실제로 개선됐다.
- client-visible stale duration p99가 낮아졌다.
- 모든 Pub/Sub 유실을 복구한다.

위 네 문장은 Docker 기반 멀티 WAS 부하테스트와 Grafana 캡처 이후에만 수치와 함께 주장한다.
