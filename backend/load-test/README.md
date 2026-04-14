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
| `scenarios/racing-tap.yml` | 30 | 8 | 풀 시퀀스(준비 → 게임 시작 → 110초 탭) | 최대 부하 |

룸 수/인원은 시나리오 yml의 `variables.roomCount`, `variables.guestCount`로 조절.

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
