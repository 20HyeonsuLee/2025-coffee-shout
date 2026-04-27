# Room Version 부하테스트 변경 Repomap

작성일: 2026-04-27

## 보는 순서

1. `load-test/scenarios/room-version-storm.yml`
2. `load-test/processor.js`
3. `load-test/publish/ready.js`
4. `load-test/helpers/connect-websocket.js`
5. `src/main/java/coffeeshout/room/infra/redis/RedisRoomRepository.java`
6. `src/main/java/coffeeshout/global/messaging/PubSubSubscriber.java`
7. `src/main/java/coffeeshout/global/metric/PubSubMetricService.java`
8. `monitor/grafana/dashboards/room-version-consistency-dashboard.json`
9. `_workspace/mission_room_version_consistency/portfolio_report.ko.md`

## 한 줄 요약

부하 테스트는 20개 방에서 guest ready 변경을 반복 발생시켜 Redis roomVersion 증가, Redis Pub/Sub 발행/수신, subscriber gap 감지, snapshot resync metric을 관측하는 구조다. Grafana는 한국어 dashboard와 image renderer로 포트폴리오 캡처까지 남긴다.

## 실행 진입점

| 파일 | 라인 | 역할 |
|---|---:|---|
| `load-test/package.json` | 6-10 | `npm run test:room-version` script 추가 |
| `load-test/scenarios/room-version-storm.yml` | 1-16 | target, processor, room/guest/round 변수 정의 |
| `load-test/scenarios/room-version-storm.yml` | 18-24 | scenario 본문. setup 후 `sendReadyStorm` 실행 |
| `load-test/README.md` | 15-19 | 실행 명령 목록 |
| `load-test/README.md` | 30-39 | 시나리오별 목적과 roomVersion 기대값 설명 |
| `load-test/README.md` | 41-56 | PromQL 관측 포인트와 Grafana renderer 캡처 안내 |

실행 예:

```bash
cd load-test
TARGET_HOST=http://localhost:8000 npm run test:room-version -- --output ../_workspace/mission_room_version_consistency/evidence/room-version-storm-report.json
```

## 부하 생성 흐름

| 파일 | 라인 | 역할 |
|---|---:|---|
| `load-test/processor.js` | 1-3 | WebSocket helper, ready publisher, setup import |
| `load-test/processor.js` | 7-17 | Artillery function export. `sendReadyStorm`를 scenario에서 호출 가능하게 노출 |
| `load-test/publish/ready.js` | 1-31 | 모든 방/플레이어 STOMP client를 순회하며 `/app/room/{joinCode}/update-ready` publish |
| `load-test/publish/ready.js` | 43-63 | `readyStormRounds`, `readyStormIntervalMs` 기준으로 ready true/false 반복 publish |
| `load-test/helpers/connect-websocket.js` | 25-37 | `/topic/room/{joinCode}` 구독 및 room-topic message count 로그 |
| `load-test/helpers/connect-websocket.js` | 60-77 | STOMP client 저장 후 Artillery setup 다음 단계로 진행 |

주의:

- host ready 변경은 도메인 정책상 publish되지 않는다.
- `room-version-storm.yml`의 기본 기대 publish 수는 `20 rooms * 7 guests * 20 rounds = 2800 PLAYER_READY`다.

## Redis roomVersion 발행 경로

| 파일 | 라인 | 역할 |
|---|---:|---|
| `src/main/java/coffeeshout/global/messaging/PubSubEnvelope.java` | 7-22 | Pub/Sub envelope에 `eventId`, `eventType`, `joinCode`, `payloadJson`, `version`, `publishedAt`, `originInstanceId` 포함 |
| `src/main/java/coffeeshout/room/infra/redis/RedisRoomRepository.java` | 39-50 | Redis key/channel/eventType 상수. `room:%s:version` 추가 |
| `src/main/java/coffeeshout/room/infra/redis/RedisRoomRepository.java` | 115-125 | 방 삭제 시 version key까지 정리 |
| `src/main/java/coffeeshout/room/infra/redis/RedisRoomRepository.java` | 131-145 | player add/ready 변경 후 versioned publish |
| `src/main/java/coffeeshout/room/infra/redis/RedisRoomRepository.java` | 173-187 | room create 후 versioned publish |
| `src/main/java/coffeeshout/room/infra/redis/RedisRoomRepository.java` | 207-223 | publish 직전 Redis `INCR`로 roomVersion 증가, TTL 갱신, publish metric 기록 |
| `src/main/java/coffeeshout/room/infra/redis/RedisRoomRepository.java` | 225-239 | payload/envelope에 `version`, `eventId`, `publishedAt`, `originInstanceId` 삽입 |

핵심 판단:

- Redis Pub/Sub 자체를 ordered log로 보지 않는다.
- Redis snapshot이 SSOT이고, Pub/Sub envelope는 전파 신호다.
- 모든 상태 변경 publish에 Redis 소유 version을 태워 subscriber가 검증한다.

## Subscriber 정합성 방어

| 파일 | 라인 | 역할 |
|---|---:|---|
| `src/main/java/coffeeshout/global/messaging/PubSubSubscriber.java` | 37-42 | serializer, roomService, messagingTemplate, selfInstanceId, metric, `lastSeenVersion` 주입 |
| `src/main/java/coffeeshout/global/messaging/PubSubSubscriber.java` | 46-58 | 메시지 수신, receive metric, 자기 메시지 skip 시 version만 mark |
| `src/main/java/coffeeshout/global/messaging/PubSubSubscriber.java` | 60-67 | `version <= lastSeenVersion`이면 stale/duplicate drop |
| `src/main/java/coffeeshout/global/messaging/PubSubSubscriber.java` | 72-84 | propagation delay 기록, gap이면 snapshot broadcast + resync metric |
| `src/main/java/coffeeshout/global/messaging/PubSubSubscriber.java` | 92-104 | version decision: `IN_ORDER`, `GAP`, `STALE_OR_DUPLICATE` |
| `src/main/java/coffeeshout/global/messaging/PubSubSubscriber.java` | 113-127 | room event만 WebSocket full-state broadcast 대상으로 처리 |
| `src/main/java/coffeeshout/global/messaging/PubSubSubscriber.java` | 137-151 | Redis snapshot을 다시 읽어 `/topic/room/{joinCode}`로 full-state broadcast |

## Metric 계측

| 파일 | 라인 | Metric | 의미 |
|---|---:|---|---|
| `src/main/java/coffeeshout/global/metric/PubSubMetricService.java` | 20-25 | `pubsub.message.published.total` | Pub/Sub publish 총량 |
| `src/main/java/coffeeshout/global/metric/PubSubMetricService.java` | 27-32 | `pubsub.message.received.total` | Subscriber 수신 총량 |
| `src/main/java/coffeeshout/global/metric/PubSubMetricService.java` | 34-38 | `pubsub.message.self.skip.total` | 자기 메시지 skip |
| `src/main/java/coffeeshout/global/metric/PubSubMetricService.java` | 40-45 | `pubsub.message.stale.drop.total` | stale/duplicate drop |
| `src/main/java/coffeeshout/global/metric/PubSubMetricService.java` | 47-52 | `pubsub.message.gap.detected.total` | version gap 감지 |
| `src/main/java/coffeeshout/global/metric/PubSubMetricService.java` | 54-64 | `room.snapshot.resync.total`, `room.snapshot.resync.duration` | Redis snapshot 재동기화 횟수/시간 |
| `src/main/java/coffeeshout/global/metric/PubSubMetricService.java` | 66-72 | `pubsub.propagation.delay` | publish 시각부터 subscriber 수신까지 지연 |

## 테스트 증거

| 파일 | 라인 | 검증 |
|---|---:|---|
| `src/test/java/coffeeshout/global/messaging/PubSubSubscriberVersionTest.java` | 52-67 | in-order 원격 메시지는 snapshot broadcast |
| `src/test/java/coffeeshout/global/messaging/PubSubSubscriberVersionTest.java` | 69-85 | 이미 처리한 version은 stale/duplicate drop |
| `src/test/java/coffeeshout/global/messaging/PubSubSubscriberVersionTest.java` | 87-106 | version gap이면 snapshot resync metric 기록 |
| `src/test/java/coffeeshout/global/messaging/PubSubSubscriberVersionTest.java` | 108-125 | 자기 메시지는 broadcast skip, version은 처리한 것으로 기록 |
| `src/test/java/coffeeshout/global/messaging/PubSubSubscriberVersionTest.java` | 127-146 | room event가 아닌 eventType도 예외 없이 skip |
| `src/test/java/coffeeshout/concurrency/LuaAtomicityConcurrencyTest.java` | 116-151 | 동시 입장 성공 횟수만큼 Redis roomVersion 증가 |

검증 명령:

```bash
./gradlew test --tests coffeeshout.global.messaging.PubSubSubscriberVersionTest --tests coffeeshout.concurrency.DistributedLockConcurrencyTest --no-configuration-cache
./gradlew test --no-configuration-cache
```

## Grafana / 캡처 경로

| 파일 | 라인 | 역할 |
|---|---:|---|
| `monitor/docker-compose.yml` | 55-78 | Grafana에 remote renderer 설정 연결 |
| `monitor/docker-compose.yml` | 80-93 | `coffee-shout-grafana-image-renderer:local` renderer 서비스 |
| `monitor/grafana/renderer/Dockerfile` | 1-14 | `grafana-image-renderer` 기반, `fonts-noto-cjk` 설치 |
| `monitor/grafana/renderer/local.conf` | 1-21 | 한국어 fallback을 `Noto Sans CJK KR`로 고정 |
| `monitor/grafana/dashboards/room-version-consistency-dashboard.json` | 10-71 | 중복/역전 메시지 Drop stat |
| `monitor/grafana/dashboards/room-version-consistency-dashboard.json` | 73-134 | Version Gap 감지 stat |
| `monitor/grafana/dashboards/room-version-consistency-dashboard.json` | 136-197 | Snapshot 재동기화 stat |
| `monitor/grafana/dashboards/room-version-consistency-dashboard.json` | 199-260 | 멀티 WAS 전파 배수 stat |

캡처 명령:

```bash
curl -fS -u admin:admin \
  -o _workspace/mission_room_version_consistency/evidence/grafana_screenshots/room-version-dashboard-renderer.png \
  'http://localhost:3000/render/d/room-version-consistency/room-version-consistency?orgId=1&from=now-30m&to=now&width=1600&height=1200&tz=Asia%2FSeoul'
```

## Evidence / 보고서

| 파일 | 라인 | 내용 |
|---|---:|---|
| `_workspace/mission_room_version_consistency/portfolio_report.ko.md` | 1-13 | 결론과 Grafana 캡처 이미지 |
| `_workspace/mission_room_version_consistency/portfolio_report.ko.md` | 55-67 | 구현 범위 |
| `_workspace/mission_room_version_consistency/portfolio_report.ko.md` | 69-90 | 부하 테스트 조건과 실행 명령 |
| `_workspace/mission_room_version_consistency/portfolio_report.ko.md` | 92-115 | 측정 결과와 해석 |
| `_workspace/mission_room_version_consistency/portfolio_report.ko.md` | 117-140 | Grafana 증거와 renderer 설명 |
| `_workspace/mission_room_version_consistency/evidence/room-version-storm-report.json` | - | Artillery raw report |
| `_workspace/mission_room_version_consistency/evidence/grafana_screenshots/room-version-dashboard-renderer.png` | - | Grafana renderer 캡처 |

## Runtime 전제

| 파일 | 라인 | 역할 |
|---|---:|---|
| `src/main/resources/application.yml` | 23-38 | local/multi-WAS 부팅에 필요한 S3 bucket, QR 기본값 |
| `src/main/resources/application.yml` | 47-69 | Prometheus endpoint와 tracing 설정 |
| `docker-compose.multi.yml` | - | 2대 WAS + nginx + Redis + MySQL 실행용 기존 compose |
| `monitor/prometheus/prometheus.yml` | - | Spring Boot app scrape 설정 |

## 커밋 시 포함할 직접 관련 파일

아래 파일들은 roomVersion 정합성 검증/부하테스트/evidence에 직접 연결된다.

- `load-test/README.md`
- `load-test/package.json`
- `load-test/processor.js`
- `load-test/publish/ready.js`
- `load-test/helpers/connect-websocket.js`
- `load-test/scenarios/room-version-storm.yml`
- `src/main/java/coffeeshout/global/messaging/PubSubEnvelope.java`
- `src/main/java/coffeeshout/global/messaging/PubSubSubscriber.java`
- `src/main/java/coffeeshout/global/metric/PubSubMetricService.java`
- `src/main/java/coffeeshout/room/infra/redis/RedisRoomRepository.java`
- `src/test/java/coffeeshout/global/messaging/PubSubSubscriberVersionTest.java`
- `src/test/java/coffeeshout/concurrency/LuaAtomicityConcurrencyTest.java`
- `monitor/docker-compose.yml`
- `monitor/grafana/dashboards/room-version-consistency-dashboard.json`
- `monitor/grafana/renderer/Dockerfile`
- `monitor/grafana/renderer/local.conf`
- `_workspace/mission_room_version_consistency/implementation_report.md`
- `_workspace/mission_room_version_consistency/portfolio_report.ko.md`
- `_workspace/mission_room_version_consistency/load_test_repomap.md`
- `_workspace/mission_room_version_consistency/evidence/room-version-storm-report.json`
- `_workspace/mission_room_version_consistency/evidence/grafana_screenshots/room-version-dashboard-renderer.png`

주의: 현재 worktree에는 Lua 제거, 메뉴/QR/S3 관련 삭제, 기존 dashboard 대규모 변경 등 다른 작업 흔적도 함께 있다. 이 repomap 커밋에는 위 직접 관련 파일만 포함하는 것이 안전하다.
