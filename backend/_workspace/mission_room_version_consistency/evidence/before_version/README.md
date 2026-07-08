# Version 도입 전 Grafana 캡처

## 기준 커밋

- 실행 기준: `9455450 feat(cs-troubleshoot): Sprint 3 수치 검증 인프라`
- 목적: `roomVersion` 도입 전 Redis Pub/Sub 전파 구조에서 관측 가능했던 지표와 한계를 남긴다.
- 제한: 가장 가까운 pre-version 커밋 `9e210b2`는 Menu/QR 도메인 삭제 후 참조 정리가 덜 되어 Docker build가 실패했다. 따라서 실행 가능한 pre-version baseline으로 `9455450`을 사용했다.

## 실행 조건

- WAS 2대
- Nginx reverse proxy
- Redis Pub/Sub
- Prometheus + Grafana
- Artillery profile: `20 rooms x 8 players x 20 ready rounds`

```bash
git worktree add --detach /tmp/coffee-shout-before-version-9455450 9455450
cd /tmp/coffee-shout-before-version-9455450/backend
docker compose -f docker-compose.multi.yml up -d --build

cd monitor
docker compose up -d

docker run --rm -d \
  --name before-grafana-renderer \
  --network monitor_monitoring \
  -p 8083:8081 \
  -e AUTH_TOKEN=coffee-shout-local-renderer \
  -e TZ=Asia/Seoul \
  coffee-shout-grafana-image-renderer:local

docker compose -f docker-compose.yml -f docker-compose.renderer.override.yml up -d --force-recreate grafana

cd /Users/leehyeonsu/home/woowa/project/2025-coffee-shout\(fork\)/2025-coffee-shout/backend/load-test
TARGET_HOST=http://localhost:8000 npm run test:room-version -- --output ../_workspace/mission_room_version_consistency/evidence/before_version/before-version-room-storm-report.json
```

`docker-compose.renderer.override.yml`은 캡처 시점에 아래 내용으로 임시 생성했다. Dashboard와 metric은 `9455450` 기준이고, renderer는 PNG 캡처를 위한 어댑터로만 붙였다.

```yaml
services:
  grafana:
    environment:
      - GF_RENDERING_SERVER_URL=http://before-grafana-renderer:8081/render
      - GF_RENDERING_CALLBACK_URL=http://grafana:3000/
      - GF_RENDERING_RENDERER_TOKEN=coffee-shout-local-renderer
      - GF_LOG_FILTERS=rendering:debug
```

대표 panel capture:

```bash
curl -fsS -u admin:admin \
  'http://localhost:3000/render/d-solo/coffee-shout-app/coffee-shout-application-metrics?orgId=1&panelId=221&from=1777342070000&to=1777342180000&width=1200&height=600&tz=Asia%2FSeoul&theme=light' \
  -o before-version-pubsub-published-vs-received.png
```

## Evidence

- `before-version-pubsub-published-vs-received.png`: 도입 전 Pub/Sub 발행/수신 패널
- `before-version-pubsub-self-skip.png`: 도입 전 self-skip 패널
- `before-version-pubsub-propagation-delay.png`: 도입 전 propagation delay 패널
- `before-version-room-storm-report.json`: Artillery 실행 결과
- `before-version-actuator-metrics.txt`: app actuator에서 직접 확인한 Pub/Sub counter 및 version/gap/resync/stale metric 부재

## 해석

- 도입 전에는 `pubsub_message_published_total`, `pubsub_message_received_total`, `pubsub_message_self_skip_total`까지만 서버가 직접 관측했다.
- `room_version`, `gap`, `snapshot resync`, `stale drop` 계열 지표는 app actuator 기준으로 존재하지 않았다.
- 따라서 도입 전 Grafana는 Pub/Sub 전파량은 보여주지만, 메시지 순서 역전/gap을 탐지하거나 복구했는지는 설명하지 못한다.
