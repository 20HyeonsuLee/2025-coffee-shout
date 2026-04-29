# Room Version Consistency Implementation Report

Date: 2026-04-27

Korean portfolio report: `_workspace/mission_room_version_consistency/portfolio_report.ko.md`

## Verdict

Implemented the server-side room version recovery path.

- Redis owns `room:{joinCode}:version`.
- Every Pub/Sub publish increments the Redis version and includes `eventId`, `version`, `originInstanceId`, and `eventType`.
- Subscribers keep local `lastSeenVersion[joinCode]`.
- `version <= lastSeenVersion` is dropped as stale/duplicate.
- `version > lastSeenVersion + 1` is recorded as a gap and triggers Redis snapshot read + WebSocket full-state broadcast for room player-list events.
- Gap recovery is protected by room-scoped single-flight/coalescing, version-aware resync cooldown, jitter, and bounded retry so concurrent recovery requests do not blindly multiply Redis snapshot reads.
- Lua was not introduced.

## Code Changes

| Area | File | Change |
|---|---|---|
| Envelope contract | `src/main/java/coffeeshout/global/messaging/PubSubEnvelope.java` | Added `eventId` and `version`. |
| Redis SSOT version | `src/main/java/coffeeshout/room/infra/redis/RedisRoomRepository.java` | Added `room:%s:version`, `INCR`, TTL, delete cleanup, versioned payload/envelope. |
| Subscriber recovery | `src/main/java/coffeeshout/global/messaging/PubSubSubscriber.java` | Added stale drop, gap detection, self-message version tracking, safe unknown event handling. |
| Snapshot herd guard | `src/main/java/coffeeshout/global/messaging/SnapshotResyncCoordinator.java` | Added room-scoped single-flight, coalescing, version-aware cooldown, jitter/backoff, and retry/fail metrics. |
| Metrics | `src/main/java/coffeeshout/global/metric/PubSubMetricService.java` | Added stale/gap/resync counters, snapshot read/coalescing/cooldown/retry/fail counters, and resync/propagation timers. |
| Unit test | `src/test/java/coffeeshout/global/messaging/PubSubSubscriberVersionTest.java` | Covers in-order, duplicate/stale, gap resync, self skip, unknown event type. |
| Unit test | `src/test/java/coffeeshout/global/messaging/SnapshotResyncCoordinatorTest.java` | Covers same-room coalescing, higher-version safety reread, cooldown skip, cooldown bypass for newer version, retry. |
| Concurrency test | `src/test/java/coffeeshout/concurrency/LuaAtomicityConcurrencyTest.java` | Added Redis roomVersion assertion under concurrent room entry. |
| Load test | `load-test/scenarios/room-version-storm.yml` | Adds ready storm: 20 rooms x 8 players, 7 guests x 20 ready rounds. |
| Grafana | `monitor/grafana/dashboards/room-version-consistency-dashboard.json` | Adds dashboard for publish/receive, stale drop, gap, resync, latency, snapshot read amplification, coalescing, cooldown. |

## Metrics To Capture

```promql
sum(rate(pubsub_message_published_total[1m])) by (eventType)
sum(rate(pubsub_message_received_total[1m])) by (eventType)
increase(pubsub_message_stale_drop_total[5m])
increase(pubsub_message_gap_detected_total[5m])
increase(room_snapshot_resync_total[5m])
increase(room_snapshot_read_total[5m])
increase(room_snapshot_resync_coalesced_total[5m])
increase(room_snapshot_resync_cooldown_skip_total[5m])
increase(room_snapshot_resync_retry_total[5m])
increase(room_snapshot_resync_failed_total[5m])
(sum(room_snapshot_read_total) or vector(0)) / clamp_min((sum(pubsub_message_gap_detected_total) or vector(0)), 1)
histogram_quantile(0.95, sum(rate(pubsub_propagation_delay_seconds_bucket[5m])) by (le))
histogram_quantile(0.95, sum(rate(room_snapshot_resync_duration_seconds_bucket[5m])) by (le))
```

## Verification

Passed:

```bash
./gradlew test --tests coffeeshout.global.messaging.PubSubSubscriberVersionTest --tests coffeeshout.concurrency.DistributedLockConcurrencyTest --no-configuration-cache
./gradlew test --tests coffeeshout.global.messaging.PubSubSubscriberVersionTest --tests coffeeshout.global.messaging.SnapshotResyncCoordinatorTest --no-configuration-cache
./gradlew test --no-configuration-cache
node --check load-test/processor.js
node --check load-test/publish/ready.js
node --check load-test/helpers/connect-websocket.js
node -e "JSON.parse(require('fs').readFileSync('monitor/grafana/dashboards/room-version-consistency-dashboard.json','utf8'))"
```

Latest server herd patch verification on 2026-04-29:

```bash
./gradlew test --tests coffeeshout.global.messaging.PubSubSubscriberVersionTest --tests coffeeshout.global.messaging.SnapshotResyncCoordinatorTest --no-configuration-cache
node -e "JSON.parse(require('fs').readFileSync('monitor/grafana/dashboards/room-version-consistency-dashboard.json','utf8'))"
git diff --check -- <room-version files>
```

Result:

- Messaging/resync unit tests: PASS.
- Grafana dashboard JSON parse: PASS.
- Whitespace check for touched files: PASS.
- `DistributedLockConcurrencyTest`: BLOCKED in the current shell because Docker daemon is not reachable at `/Users/leehyeonsu/.docker/run/docker.sock`.

## Load Test Evidence

Executed against local multi-WAS stack on 2026-04-27.

```bash
docker compose -f docker-compose.multi.yml up -d --build
cd monitor && docker compose up -d
cd ../load-test
TARGET_HOST=http://localhost:8000 npm run test:room-version -- --output ../_workspace/mission_room_version_consistency/evidence/room-version-storm-report.json
```

Scenario:

- 20 rooms
- 8 players per room
- Host ready changes are ignored by domain policy
- 7 guests per room publish ready true/false for 20 rounds

Result:

| Metric | Value |
|---|---:|
| Artillery VUs completed | 1 |
| Artillery VUs failed | 0 |
| Total scenario time | 16s |
| Session p95 | 14917.2ms |
| Redis `room:*:version` keys | 20 |
| Redis roomVersion distribution | 20 rooms at version 148 |
| `pubsub_message_published_total{PLAYER_READY}` | 2800 |
| `pubsub_message_published_total{PLAYER_LIST_UPDATE}` | 140 |
| `pubsub_message_published_total{ROOM_CREATE}` | 20 |
| `pubsub_message_received_total{PLAYER_READY}` | 5600 |
| `pubsub_message_gap_detected_total` | 17 |
| `room_snapshot_resync_total` | 17 |
| `pubsub_message_stale_drop_total` | 0 |
| Pub/Sub propagation p95 | ~2.07ms |
| Snapshot resync average | ~3.02ms |

Interpretation:

- `roomVersion=148` matches `8 setup versions + 7 guests * 20 ready rounds`.
- Received count is roughly 2x published count because both WAS instances subscribe to the shared Redis Pub/Sub channel.
- The observed 17 gaps triggered 17 snapshot resyncs, which is exactly the recovery behavior this sprint is trying to prove.

Evidence artifacts:

- `_workspace/mission_room_version_consistency/evidence/room-version-storm-report.json`
- `_workspace/mission_room_version_consistency/evidence/room-version-load-summary.svg`
- `_workspace/mission_room_version_consistency/evidence/room-version-load-summary.png`
- `_workspace/mission_room_version_consistency/evidence/grafana_screenshots/room-version-dashboard-renderer.png`

Note: Grafana dashboard provisioning now includes a remote image renderer service. The renderer image is built locally with `fonts-noto-cjk` and fontconfig prefers `Noto Sans CJK KR` for Korean fallback, so Korean dashboard screenshots are reproducible without relying on the desktop browser canvas.

## Important Portfolio Framing

Do not claim total ordering from Redis Pub/Sub. The accurate claim is:

> Redis remains the authoritative room snapshot store. Pub/Sub is treated as a lossy/duplicable propagation channel. Each propagated message carries a Redis-owned roomVersion; subscribers drop stale messages and recover from observed gaps by re-reading the Redis snapshot and broadcasting full state.

Next useful evidence step: run a fresh load test immediately before final portfolio capture, then regenerate the Grafana screenshot from the renderer endpoint so the dashboard time window contains the latest spike.
