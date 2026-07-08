# Sprint 3 도입 검증 로그

Sprint 3는 "재현"이 아닌 **"Redis SSOT 도입 검증"**. Sprint 1-2가 DOC_ONLY로 baseline에 split-brain 자체가 없었기 때문.

## 실행 환경

- 컨테이너: `mysql:8.0` + `redis:7.2` + `backend-app`
- WAS 수: 1대
- profile: `docker`
- 검증 일시: 2026-04-15

## Step A — 인프라 + 빈 설정

### 변경 파일

| 파일 | 변경 내용 |
|------|-----------|
| `docker-compose.yml` | `redis:7.2` 서비스 추가, app `depends_on` 에 redis 추가 |
| `application-docker.yml` | `spring.data.redis.host: redis`, `port: 6379`, `ssl.enabled: false` |
| `application.yml` | `ssl.enabled: true → false` (기본값 정상화) |
| `CoffeeShoutApplication.java` | `RedisAutoConfiguration` exclude 제거 (활성화) |
| `global/config/redis/RedisConfig.java` | 신규 — RedisTemplate, StringRedisTemplate, ListenerContainer, ChannelTopic 빈 |
| `global/config/redis/LuaScriptConfig.java` | 신규 — 5개 Lua 스크립트를 `DefaultRedisScript` 빈으로 등록 |
| `global/messaging/InstanceIdConfig.java` | 신규 — WAS 고유 UUID `selfInstanceId` 빈 |

### 검증
- `./gradlew compileJava` PASS
- `docker compose up -d` → mysql/redis/app 모두 healthy
- `/actuator/health`: `{"status":"UP", components: {db:UP, redis:{version:"7.2.13"}, ...}}`

## Step B — Redis SSOT 구현

### 신규 파일

| 파일 | 역할 |
|------|------|
| `src/main/resources/lua/claim_joincode.lua` | SET NX EX 원자 래퍼 |
| `src/main/resources/lua/create_room.lua` | meta + players + TTL + PUBLISH 원자 (joincode 유일성은 호출 전 선점) |
| `src/main/resources/lua/enter_room.lua` | 정원 + SADD + PUBLISH 원자 (등록만, 호출 미연결) |
| `src/main/resources/lua/toggle_ready.lua` | SISMEMBER + HSET + PUBLISH 원자 (등록만, 호출 미연결) |
| `src/main/resources/lua/remove_player.lua` | SREM + HDEL x3 + PUBLISH 원자 (등록만, 호출 미연결) |
| `global/messaging/PubSubEnvelope.java` | Pub/Sub 전파 envelope record |
| `global/messaging/PubSubEnvelopeSerializer.java` | envelope ↔ JSON 직렬화 |
| `global/messaging/PubSubSubscriber.java` | `coffeeshout:events` 채널 구독자, 자기 메시지 skip |
| `room/infra/redis/RedisPlayerData.java` | Player ↔ Hash 직렬화 DTO |
| `room/infra/redis/RedisRoomRepository.java` | Redis 기반 RoomRepository (`@Profile("!test")`, `@Primary`) |
| `room/infra/RedisJoinCodeRepository.java` | Redis 기반 JoinCodeRepository (`@Profile("!test")`, `@Primary`) |
| `global/websocket/RedisStompSessionManager.java` | Redis Hash 기반 세션 매핑 (`StompSessionManager` 서브클래스) |

### 수정 파일

| 파일 | 변경 |
|------|------|
| `room/domain/Room.java` | `reconstruct()` 팩터리 추가 (Redis 재조립용) |
| `room/domain/player/Player.java` | `reconstruct()` 팩터리 추가 |
| `room/domain/player/Players.java` | `reconstruct()` 정적 팩터리 추가 |
| `MemoryRoomRepository.java` / `MemoryJoinCodeRepository.java` / `StompSessionManager.java` | `@Profile("test")` 추가 |
| `src/test/resources/application-test.yml` | `RedisAutoConfiguration` exclude 추가 |

### 프로파일 전략

| profile | 활성 구현체 |
|---|---|
| `test` | Memory 구현체 (단위 테스트가 Redis 없이 동작) |
| `!test` (docker, local 등) | Redis 구현체 |

### 회귀 검증
- `./gradlew test` PASS — 422 tests, 0 failed (2분 11초)

## Step C — 통합 검증

### 방 생성 동작 확인

```bash
curl -X POST http://localhost:8080/rooms \
  -H "Content-Type: application/json" \
  -d '{"playerName":"호스트","menu":{"id":1,"customName":null,"temperature":"ICE"}}'
# → {"joinCode":"PB3H"}
```

### Redis 키 분해 검증

```
joincode:PB3H        ← claim_joincode.lua (RedisJoinCodeRepository)
room:PB3H:meta       ← create_room.lua (HSET hostName/state/gameType/createdAt/maxPlayers)
room:PB3H:players    ← create_room.lua (SADD host)
room:PB3H:ready      ← storePlayerData (HSET host=false)
room:PB3H:player_data ← storePlayerData (HSET host=<JSON>)
```

`room:PB3H:meta` HGETALL:
```
hostName  호스트
state     READY
gameType  NONE
createdAt 1776215531084
maxPlayers 9
```
→ 설계 4.2 스키마와 일치.

### artillery 부하 시나리오

| 시나리오 | 결과 | 비고 |
|---|---|---|
| `scenarios/baseline.yml` (1방 × 2명) | **PASS** (1 vusers, 0 failed, 3.1s) | 방 생성 + 입장 + ready 토글 1회 |
| `scenarios/ready-toggle.yml` (5방 × 8명) | **PASS** (1 vusers, 0 failed, 7.4s) | 다중 룸 ready 토글 |

artillery 종료 후 Redis 잔존 키 35개 (5방 × 5종 + α). TTL(`room.removalDelay`)에 따라 자동 만료.

### Lua 스크립트 디버깅 사례

초기 실행 시 `create_room.lua`가 `-1` (중복) 반환 → meta/players 누락 → QR 핸들러 "방 없음" 에러 연쇄.

**원인**: `JoinCodeRepository`가 사전에 `claim_joincode.lua`로 `joincode:{code}`를 선점한 후 `create_room.lua`가 같은 키에 SETNX 시도 → 중복 검출 → 분기 실패.

**수정**: `create_room.lua`의 SETNX를 `EXISTS room:{joinCode}:meta` 체크로 변경. joinCode 유일성은 JoinCodeRepository 책임, create_room은 meta/players 작성 + TTL + PUBLISH만 담당. TTL 동기화를 위해 `joincode:{code}`에도 EXPIRE 반영.

수정 후: 5개 키 모두 정상 생성. QR 핸들러도 동시 정상화.

## Lua 호출 경로 연결 완료 + 도메인 침투 제거 (후속 리팩터링)

| 스크립트 | 빈 등록 | 호출 경로 | 상태 |
|---|---|---|---|
| `claim_joincode.lua` | ✓ | `RedisJoinCodeRepository.save` | **연결** |
| `create_room.lua` | ✓ | `RedisRoomRepository.createRoom` | **연결** |
| `enter_room.lua` | ✓ | `RedisRoomRepository.addPlayer` | **연결** |
| `toggle_ready.lua` | ✓ | `RedisRoomRepository.updatePlayerReady` | **연결** |
| `remove_player.lua` | ✓ | `RedisRoomRepository.removePlayer` | **연결** |

### 1. 도메인 침투 제거

| 위치 | 변경 전 | 변경 후 |
|---|---|---|
| `Room.java` | `reconstruct()` + "Redis 재조립 전용" 주석 | `ofStored()` — "저장소에서 읽어 온 값으로 복원" |
| `Player.java` | `reconstruct()` + `player.colorIndex = ...` 직접 필드 쓰기 | `ofStored()` + 전체 인자 생성자 (모든 필드 생성자 통해서만 세팅) |
| `Players.java` | `reconstruct()` | `ofStored()` |

검증: `grep -rn "Redis\|redis" src/main/java/coffeeshout/room/domain/` → **0건**.

### 2. Mapper 분리 (책임 분리)

신규 `RedisRoomMapper.java` (88줄):
- `toRoom(joinCode, meta, playerDataMap)` — Hash → Room 재조립
- `toJson(player)` — Player → JSON
- 내부 `fromJson`, `toPlayer` private 메서드

`RedisPlayerData`는 순수 DTO로 축소 (from만 남김, toPlayer 삭제). 도메인 팩터리 호출은 Mapper 내부로 고립.

### 3. Repository 인터페이스 확장

```java
void addPlayer(JoinCode, Player);
void updatePlayerReady(JoinCode, PlayerName, boolean);
void removePlayer(JoinCode, PlayerName);
```

### 4. 호출부 교체

| 호출부 | 변경 |
|---|---|
| `RoomCommandService.joinGuest` | `save(room)` → `room.joinGuest(...)` + `roomRepository.addPlayer(joinCode, guest)` |
| `RoomService.changePlayerReadyStateInternal` | `roomCommandService.save(room)` → `roomCommandService.updatePlayerReady(...)` |
| `RoomService.removePlayer` | `room.removePlayer(...)` + `roomCommandService.removePlayer(...)` 추가 |

`save()`는 최초 생성(`saveIfAbsentRoom`)과 상태-only 전이(`updateMiniGames`)에서만 유지.

### 5. Lua 실패 반환값 예외 전환 (조용히 삼키지 않음)

`enter_room` 반환값:
- `1` → OK
- `-1` (FULL) → `InvalidStateException(ROOM_FULL)`
- `-2` (DUPLICATE_NAME) → `InvalidStateException(DUPLICATE_PLAYER_NAME)`
- `-3` (ROOM_NOT_FOUND) → `NotExistElementException`

`toggle_ready` 반환값 `-1` → `NotExistElementException(NO_EXIST_PLAYER)`.

## 재검증 결과

### 빌드/테스트

| 단계 | 결과 |
|---|---|
| `./gradlew compileJava` | BUILD SUCCESSFUL |
| `./gradlew test` | BUILD SUCCESSFUL (2m 10s) |

### Docker 기동

```
backend-app-1     Up (healthy)   0.0.0.0:8080->8080
backend-mysql-1   Up (healthy)   0.0.0.0:33061->3306
backend-redis-1   Up (healthy)   0.0.0.0:6379->6379
```

### 기능 스모크

```
POST /rooms {"playerName":"host", ...}          → 200 {"joinCode":"C8UY"}
POST /rooms/C8UY {"playerName":"guest1", ...}   → 200 (Lua enter_room OK)
POST /rooms/C8UY {"playerName":"guest1", ...}   → 409 (Lua enter_room -2 DUPLICATE_NAME)

redis-cli KEYS "*"
  room:C8UY:meta / :players / :ready / :player_data
  joincode:C8UY
redis-cli SMEMBERS room:C8UY:players → host, guest1
```

### Artillery

| 시나리오 | 결과 |
|---|---|
| `baseline.yml` (1방 × 2명) | PASS (0 failed, 3.1s) |
| `ready-toggle.yml` (5방 × 8명) | PASS (0 failed, 7.4s) |

### Lua 실행 통계

`redis-cli INFO commandstats | grep evalsha`:
```
cmdstat_evalsha:calls=87, usec=1586, usec_per_call=18.23
```
— Artillery 러닝 중 Lua EVALSHA 87회 호출됨. 호출당 평균 18μs.

## 결론

- 도메인 패키지에 Redis/Spring Data 참조 제로
- `RedisRoomRepository`는 Lua 호출 경로 + 예외 전환 + Hash 쓰기만 담당 (매핑 책임은 Mapper로 분리)
- Lua 5종 전부 호출 경로 연결 — multi-WAS race 가드 (정원/중복이름/플레이어 존재 검증) 이제 Redis 층에서 원자적으로 보장
- `save(room)` 패턴은 최초 생성에만 유지, mutation은 세분화된 신규 3개 메서드로 이전

## 후속 보강 (2026-04-15 2차)

Sprint 3 설계 §4.4 positions Hash 미구현 + §9 multi-WAS 검증 스킵 두 건을 마감.

### A. RacingGame `positions` Redis 이전

| 항목 | 변경 |
|---|---|
| `src/main/resources/lua/update_positions.lua` | 신규 — HSET + EXPIRE + PUBLISH envelope 원자 |
| `global/config/redis/LuaScriptConfig.java` | `updatePositionsScript` 빈 추가 |
| `room/domain/repository/RoomRepository.java` | `updatePositions(JoinCode, Map<PlayerName, Integer>)` 계약 추가 |
| `room/infra/redis/RedisRoomRepository.java` | Lua 호출 + envelope 조립 (이벤트 타입 `RACING_POSITIONS`) |
| `room/domain/repository/MemoryRoomRepository.java` | no-op (test 프로파일은 Runner 메모리로 충분) |
| `racinggame/domain/RacingGame.java` | `getPositionsByName()` 추가 (PlayerName 키로 스냅샷 반환) |
| `racinggame/application/RacingGameService.java` | `publishRunnersMoved` 경로에서 tick마다 Redis flush. 실패 시 warn 로그 후 tick 계속 |

### B. Multi-WAS LB 구성

| 파일 | 내용 |
|---|---|
| `docker-compose.multi.yml` | mysql + redis 공유, app-1(8081) · app-2(8082) 복제, nginx(8000) round-robin |
| `nginx/nginx.conf` | `upstream coffeeshout`에 app-1:8080, app-2:8080. sticky 없음. `X-Upstream` 응답 헤더로 처리 WAS 노출 |

기동 후 `curl http://localhost:8000/actuator/health` 4회 → `X-Upstream: 172.18.0.4:8080 / 172.18.0.5:8080` 번갈아 관찰, round-robin 확인.

### C. 검증에서 드러난 피드백 루프 버그

**증상**: LB 기동 후 artillery 종료, 클라 없음에도 Redis `evalsha`/`publish` 6,000 calls/sec 지속.

**원인**: `PubSubSubscriber`가 원격 envelope 수신 시 `ApplicationEventPublisher.publishEvent(new PlayerReadyEvent(...))`를 호출 → 로컬 핸들러 `PlayerReadyEventHandler.handle`이 `changePlayerReadyStateInternal`(쓰기 경로) 실행 → Lua가 PUBLISH envelope → 또 원격 WAS가 받음 → 무한 루프. 단일 WAS에서는 자기 메시지 skip으로 감춰져 있던 문제.

```
app-1 write → Lua PUBLISH
          ↓
    app-2 subscriber receives
          ↓
    app-2 republishes PlayerReadyEvent locally
          ↓
    app-2 handler calls changePlayerReadyStateInternal → Lua PUBLISH
          ↓
    app-1 subscriber receives → app-1 republishes → handler → write → PUBLISH ...
```

**수정** (`global/messaging/PubSubSubscriber.java`):
- ApplicationEventPublisher 주입 제거
- 원격 envelope 수신 시 쓰기 핸들러를 거치지 않고 `RoomService.getPlayersInternal` + `LoggingSimpMessagingTemplate.convertAndSend`로 **읽기 + WebSocket 브로드캐스트만** 수행
- PLAYER_LIST_UPDATE / PLAYER_READY / PLAYER_KICK 모두 같은 "플레이어 목록 갱신 → `/topic/room/{joinCode}` 송신" 패턴이므로 단일 `broadcastPlayerList` 로 통합
- 자기 메시지 skip은 유지 (이중 브로드캐스트 방지)

**검증**:
- `./gradlew test` BUILD SUCCESSFUL (0 failed)
- 재배포 후 idle 30초 Redis 통계: **0 calls** (피드백 루프 제거)
- Artillery ready-toggle 통과 후에도 idle 복귀 시 0 calls

### D. Multi-WAS split-brain 검증 결과

| 검증 | 결과 |
|---|---|
| `POST /rooms` (app-1) → `POST /rooms/{code}` (app-2) 입장 | 200 OK |
| 양쪽 WAS `GET /rooms/{code}/probabilities` diff | **일치** (split-brain 없음) |
| 5게스트 무작위 WAS 분산 입장 → 6명 전원 Redis `SMEMBERS`에 존재 | PASS |
| Redis PSUBSCRIBE coffeeshout:events — originInstanceId 두 종류 관찰 | 두 WAS 각자 publish 확인 |
| `PUBSUB NUMSUB coffeeshout:events` | **2** (두 WAS 모두 구독) |
| Artillery ready-toggle through `nginx:8000` | 0 failed |
| app-1 이벤트 처리 23건 vs app-2 35건 | round-robin 분산 처리 확인 |

Sprint 3 본문에서 "Phase 2 선택"으로 미뤘던 multi-WAS 확장 검증과 §4.4 positions Hash 구현이 마무리됐다.
