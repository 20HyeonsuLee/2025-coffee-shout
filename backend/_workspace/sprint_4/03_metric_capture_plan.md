# Sprint 4 Metric Capture Plan

작성일: 2026-04-29

## 목표 Claim

검증할 claim:

> gap/reconnect/WAS restart 시점의 snapshot recovery가 Redis snapshot read를 무제한 증폭시키지 않도록 방 단위 single-flight/coalescing/cooldown/retry를 적용했고, Prometheus/Grafana로 full sync amplification을 관측할 수 있다.

## 현재 상태

| 항목 | 상태 |
|---|---|
| 서버 구현 | 완료 |
| 단위 테스트 | 완료 |
| Grafana 패널 | 완료 |
| Docker 기반 멀티 WAS 부하테스트 | Docker daemon 미가동으로 미실행 |
| 최신 Grafana screenshot | 미캡처 |

## 필요한 Metric

| Metric | PromQL | PASS 기준 |
|---|---|---|
| gap 감지량 | `sum(pubsub_message_gap_detected_total{job="spring-boot-app"})` | 시나리오에서 1 이상 |
| 실제 snapshot read | `sum(room_snapshot_read_total{job="spring-boot-app"})` | gap 대비 과도하게 증폭되지 않음 |
| resync 성공 | `sum(room_snapshot_resync_total{job="spring-boot-app"})` | read 중 성공량 확인 |
| coalesced request | `sum(room_snapshot_resync_coalesced_total{job="spring-boot-app"})` | herd 시나리오에서 1 이상 |
| cooldown skip | `sum(room_snapshot_resync_cooldown_skip_total{job="spring-boot-app"})` | 동일 version 반복 요청 시 1 이상 |
| retry | `sum(room_snapshot_resync_retry_total{job="spring-boot-app"})` | 실패 주입 시 1 이상 |
| failed | `sum(room_snapshot_resync_failed_total{job="spring-boot-app"})` | 정상 시나리오에서는 0 |
| full sync amplification | `(sum(room_snapshot_read_total{job="spring-boot-app"}) or vector(0)) / clamp_min((sum(pubsub_message_gap_detected_total{job="spring-boot-app"}) or vector(0)), 1)` | 정상 복구에서는 1 근처, herd 완화 시나리오에서 통제 가능 |

## 시나리오

### Scenario A. 기존 ready storm 재실행

목적:

- 기존 `roomVersion` evidence와 새 metric이 같이 나오는지 확인한다.
- `room_snapshot_read_total`과 기존 `room_snapshot_resync_total`이 정상적으로 증가하는지 본다.

명령:

```bash
docker compose -f docker-compose.multi.yml up -d --build
cd monitor && docker compose up -d
cd ../load-test
TARGET_HOST=http://localhost:8000 npm run test:room-version -- --output ../_workspace/mission_room_version_consistency/evidence/room-version-storm-report.json
```

기대:

- `pubsub_message_gap_detected_total >= 1`
- `room_snapshot_read_total >= 1`
- `room_snapshot_resync_total >= 1`
- `room_snapshot_resync_failed_total == 0`

### Scenario B. Same-room gap herd

목적:

- 같은 `joinCode`에서 gap recovery가 동시에 몰릴 때 `coalesced_total`이 증가하는지 확인한다.
- 같은 version 반복 요청이 snapshot read를 여러 번 만들지 않는지 본다.

필요 artifact:

- 같은 방에 version gap envelope를 빠르게 여러 번 주입하는 테스트 publisher 또는 JUnit integration test
- 혹은 `PubSubSubscriber` 단위 부하 테스트를 별도 fixture로 작성

기대:

- `room_snapshot_resync_coalesced_total > 0`
- `room_snapshot_read_total < pubsub_message_gap_detected_total` 또는 같은 방 중복 요청 대비 read 감소

### Scenario C. WAS restart recovery

목적:

- WAS restart 후 `lastSeenVersion` 초기화로 gap이 늘어나는 상황에서 snapshot read가 통제되는지 본다.

명령 후보:

```bash
docker compose -f docker-compose.multi.yml up -d --build
docker compose -f docker-compose.multi.yml restart app-1
cd load-test
TARGET_HOST=http://localhost:8000 npm run test:room-version -- --output ../_workspace/mission_room_version_consistency/evidence/room-version-restart-report.json
```

기대:

- restart 직후 gap/resync/read spike가 Grafana time range에 보임
- failed metric은 0
- amplification factor가 해석 가능한 범위로 유지됨

## Grafana 캡처

대상 dashboard:

- `monitor/grafana/dashboards/room-version-consistency-dashboard.json`

추가된 패널:

- Snapshot Read
- Coalesced Resync
- Cooldown Skip
- Full Sync 증폭률
- 복구 경로 보호 이벤트
- Gap 대비 Snapshot Read

renderer 캡처 예:

```bash
curl -fS -u admin:admin \
  -o _workspace/mission_room_version_consistency/evidence/grafana_screenshots/room-version-dashboard-renderer.png \
  'http://localhost:3000/render/d/room-version-consistency/room-version-consistency?orgId=1&from=now-30m&to=now&width=1600&height=1400&tz=Asia%2FSeoul'
```

## Evidence 저장 경로

| 산출물 | 경로 |
|---|---|
| Artillery raw report | `_workspace/mission_room_version_consistency/evidence/room-version-storm-report.json` |
| Restart storm report | `_workspace/mission_room_version_consistency/evidence/room-version-restart-report.json` |
| Grafana screenshot | `_workspace/mission_room_version_consistency/evidence/grafana_screenshots/room-version-dashboard-renderer.png` |
| Sprint report | `_workspace/sprint_4/02_implementation_report.md` |
| Portfolio report | `_workspace/mission_room_version_consistency/portfolio_report.ko.md` |

## 포트폴리오 문장 후보

수치 확보 전:

> Pub/Sub gap recovery가 Redis snapshot read hot key를 만들 수 있는 문제를 식별하고, 방 단위 single-flight/coalescing/cooldown/retry와 Prometheus 지표를 추가해 full sync amplification을 측정 가능한 구조로 만들었습니다.

수치 확보 후:

> 2대 WAS 환경에서 gap recovery storm을 재현하고, `room_snapshot_read_total / gap_detected_total` 증폭률을 N 이하로 유지했으며 coalesced request N건과 cooldown skip N건을 Grafana로 검증했습니다.

숫자는 반드시 실제 Grafana 캡처 후 채운다.
