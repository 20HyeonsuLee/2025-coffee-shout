# load-test — CoffeeShout 부하 테스트

Artillery + STOMP/SockJS 기반 WebSocket 부하 테스트. cs-troubleshoot 시나리오 재현/검증 도구.

## 설치

```bash
npm install
```

## 실행

타깃 호스트를 환경변수로 지정한다.

```bash
TARGET_HOST=http://localhost:8080 npm test            # baseline 스모크 (1방 × 2명, ready 1회)
TARGET_HOST=http://localhost:8080 npm run test:ready  # 5방 × 8명 ready 토글
TARGET_HOST=http://localhost:8080 npm run test:room-version # 20방 × 8명(guest 7명 ready) × 20회 ready storm
TARGET_HOST=http://localhost:8080 npm run test:racing # 30방 × 8명 레이싱 110초 연속 탭
```

직접 호출도 가능:

```bash
TARGET_HOST=http://localhost:8080 npx artillery run scenarios/baseline.yml
```

## 시나리오

| 파일 | 룸 수 | 룸당 인원 | 흐름 | 용도 |
|---|---|---|---|---|
| `scenarios/baseline.yml` | 1 | 2 | 방 생성 → ready true | 단일 WAS 동작 확인 |
| `scenarios/ready-toggle.yml` | 5 | 8 | 방 생성 → ready true | 다중 룸 메시지 브로드캐스트 |
| `scenarios/room-version-storm.yml` | 20 | 8 | 방 생성 → guest 7명 ready true/false 20회 반복 | roomVersion 증가, Pub/Sub stale/gap/resync 관측 |
| `scenarios/racing-tap.yml` | 30 | 8 | 풀 시퀀스(준비 → 게임 시작 → 110초 탭) | 최대 부하 |

룸 수/인원은 시나리오 yml의 `variables.roomCount`, `variables.guestCount`로 조절.
`room-version-storm.yml`은 `readyStormRounds`, `readyStormIntervalMs`로 같은 방 안의 순차/동시 ready 변경 압력을 조절한다.
호스트는 도메인 정책상 ready 변경이 publish되지 않으므로, 각 방의 ready publish 기대값은 `guestCount * readyStormRounds`이다.

## roomVersion 관측 포인트

Grafana/Prometheus에서 아래 지표를 캡처한다.

```promql
sum(rate(pubsub_message_published_total[1m])) by (eventType)
sum(rate(pubsub_message_received_total[1m])) by (eventType)
increase(pubsub_message_stale_drop_total[5m])
increase(pubsub_message_gap_detected_total[5m])
increase(room_snapshot_resync_total[5m])
histogram_quantile(0.95, sum(rate(pubsub_propagation_delay_seconds_bucket[5m])) by (le))
histogram_quantile(0.95, sum(rate(room_snapshot_resync_duration_seconds_bucket[5m])) by (le))
```

포트폴리오 캡처 시에는 `Room Version 정합성 검증` 대시보드에서 test window를 맞춘 뒤,
Grafana image renderer로 전체 대시보드 PNG를 저장한다.

## 구조

```
load-test/
├── package.json
├── processor.js              # Artillery 진입점 (function 노출)
├── scenarios/
│   ├── baseline.yml
│   ├── ready-toggle.yml
│   └── racing-tap.yml
├── helpers/
│   ├── set-up.js             # 방 생성 + 게스트 입장 + WebSocket 연결
│   ├── create-room.js        # POST /rooms, /rooms/{joinCode}
│   └── connect-websocket.js  # SockJS + STOMP 연결, 토픽 구독
└── publish/
    ├── ready.js              # /app/room/{joinCode}/update-ready
    └── mini-game.js          # update-minigames, START_MINI_GAME, racing-game/tap
```

## 트러블슈팅

- 서버 미기동: `cd backend && docker compose up -d` 또는 `./gradlew bootRun`
- TARGET_HOST 누락: `TARGET_HOST=http://localhost:8080 ...` 명시
- WebSocket 토픽 수신 확인은 `connect-websocket.js`의 `console.log` 출력으로
