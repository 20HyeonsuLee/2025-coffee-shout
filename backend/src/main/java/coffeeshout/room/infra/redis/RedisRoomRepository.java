package coffeeshout.room.infra.redis;

import coffeeshout.global.exception.GlobalErrorCode;
import coffeeshout.global.exception.custom.InvalidStateException;
import coffeeshout.global.exception.custom.NotExistElementException;
import coffeeshout.global.messaging.PubSubEnvelope;
import coffeeshout.global.messaging.PubSubEnvelopeSerializer;
import coffeeshout.room.domain.JoinCode;
import coffeeshout.room.domain.Room;
import coffeeshout.room.domain.RoomErrorCode;
import coffeeshout.room.domain.player.Player;
import coffeeshout.room.domain.player.PlayerName;
import coffeeshout.room.domain.repository.RoomRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

/**
 * Redis 기반 RoomRepository 구현체.
 * 상태 변경과 Pub/Sub 발행을 Lua 스크립트로 원자 실행.
 * 도메인 ↔ Hash 매핑은 RedisRoomMapper에 위임.
 */
@Slf4j
@Repository
@Primary
@Profile("!test")
public class RedisRoomRepository implements RoomRepository {

    private static final String META_KEY = "room:%s:meta";
    private static final String PLAYERS_KEY = "room:%s:players";
    private static final String READY_KEY = "room:%s:ready";
    private static final String PLAYER_DATA_KEY = "room:%s:player_data";
    private static final String POSITIONS_KEY = "room:%s:positions";
    private static final String JOINCODE_KEY = "joincode:%s";
    private static final int MAX_PLAYERS = 9;
    private static final String PLAYER_LIST_UPDATE = "PLAYER_LIST_UPDATE";
    private static final String PLAYER_READY = "PLAYER_READY";
    private static final String ROOM_CREATE = "ROOM_CREATE";
    private static final long OK = 1L;
    private static final long DUPLICATE_JOINCODE = -1L;
    private static final long FULL = -1L;
    private static final long DUPLICATE_NAME = -2L;
    private static final long ROOM_NOT_FOUND = -3L;
    private static final long PLAYER_NOT_FOUND = -1L;

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisTemplate<String, String> redisTemplate;
    private final RedisScript<Long> createRoomScript;
    private final RedisScript<Long> enterRoomScript;
    private final RedisScript<Long> toggleReadyScript;
    private final RedisScript<Long> removePlayerScript;
    private final PubSubEnvelopeSerializer envelopeSerializer;
    private final ObjectMapper objectMapper;
    private final RedisRoomMapper mapper;
    private final String selfInstanceId;

    @Value("${room.removalDelay}")
    private Duration ttl;

    public RedisRoomRepository(
            final StringRedisTemplate stringRedisTemplate,
            final RedisTemplate<String, String> redisTemplate,
            final RedisScript<Long> createRoomScript,
            final RedisScript<Long> enterRoomScript,
            final RedisScript<Long> toggleReadyScript,
            final RedisScript<Long> removePlayerScript,
            final PubSubEnvelopeSerializer envelopeSerializer,
            final ObjectMapper objectMapper,
            final RedisRoomMapper mapper,
            final @Qualifier("selfInstanceId") String selfInstanceId
    ) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.redisTemplate = redisTemplate;
        this.createRoomScript = createRoomScript;
        this.enterRoomScript = enterRoomScript;
        this.toggleReadyScript = toggleReadyScript;
        this.removePlayerScript = removePlayerScript;
        this.envelopeSerializer = envelopeSerializer;
        this.objectMapper = objectMapper;
        this.mapper = mapper;
        this.selfInstanceId = selfInstanceId;
    }

    @Override
    public Optional<Room> findByJoinCode(final JoinCode joinCode) {
        final Map<Object, Object> meta = redisTemplate.opsForHash()
                .entries(META_KEY.formatted(joinCode.getValue()));
        if (meta.isEmpty()) {
            return Optional.empty();
        }
        final Map<Object, Object> playerDataMap = redisTemplate.opsForHash()
                .entries(PLAYER_DATA_KEY.formatted(joinCode.getValue()));
        return Optional.of(mapper.toRoom(joinCode, meta, playerDataMap));
    }

    @Override
    public boolean existsByJoinCode(final JoinCode joinCode) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(META_KEY.formatted(joinCode.getValue())));
    }

    @Override
    public Room save(final Room room) {
        final String code = room.getJoinCode().getValue();
        if (existsByJoinCode(room.getJoinCode())) {
            redisTemplate.opsForHash().put(META_KEY.formatted(code), "state", room.getRoomState().name());
            room.getPlayers().forEach(player -> writePlayer(code, player));
            return room;
        }
        createRoom(room, code);
        return room;
    }

    @Override
    public void deleteByJoinCode(final JoinCode joinCode) {
        final String code = joinCode.getValue();
        redisTemplate.delete(List.of(
                META_KEY.formatted(code),
                PLAYERS_KEY.formatted(code),
                READY_KEY.formatted(code),
                PLAYER_DATA_KEY.formatted(code),
                POSITIONS_KEY.formatted(code),
                JOINCODE_KEY.formatted(code)
        ));
        log.info("방 삭제 완료: joinCode={}", code);
    }

    @Override
    public void addPlayer(final JoinCode joinCode, final Player player) {
        final String code = joinCode.getValue();
        final String envelope = buildEnvelope(PLAYER_LIST_UPDATE, code, Map.of("joinCode", code));

        final Long result = stringRedisTemplate.execute(
                enterRoomScript,
                List.of(META_KEY.formatted(code), PLAYERS_KEY.formatted(code)),
                player.getName().value(),
                String.valueOf(MAX_PLAYERS),
                envelope
        );

        validateEnterResult(result, player.getName().value());
        writePlayer(code, player);
        log.debug("플레이어 추가 완료: joinCode={}, player={}", code, player.getName().value());
    }

    @Override
    public void updatePlayerReady(final JoinCode joinCode, final PlayerName playerName, final boolean ready) {
        final String code = joinCode.getValue();
        final String envelope = buildEnvelope(PLAYER_READY, code, Map.of(
                "playerName", playerName.value(),
                "isReady", ready
        ));

        final Long result = stringRedisTemplate.execute(
                toggleReadyScript,
                List.of(PLAYERS_KEY.formatted(code), READY_KEY.formatted(code)),
                playerName.value(),
                String.valueOf(ready),
                envelope
        );

        if (result == null || result == PLAYER_NOT_FOUND) {
            throw new NotExistElementException(RoomErrorCode.NO_EXIST_PLAYER,
                    "플레이어가 존재하지 않습니다: " + playerName.value());
        }
        updatePlayerDataReady(code, playerName, ready);
    }

    @Override
    public void removePlayer(final JoinCode joinCode, final PlayerName playerName) {
        final String code = joinCode.getValue();
        final String envelope = buildEnvelope(PLAYER_LIST_UPDATE, code, Map.of(
                "joinCode", code,
                "playerName", playerName.value()
        ));

        stringRedisTemplate.execute(
                removePlayerScript,
                List.of(
                        PLAYERS_KEY.formatted(code),
                        READY_KEY.formatted(code),
                        POSITIONS_KEY.formatted(code)
                ),
                playerName.value(),
                envelope
        );
        redisTemplate.opsForHash().delete(PLAYER_DATA_KEY.formatted(code), playerName.value());
        log.debug("플레이어 제거 완료: joinCode={}, player={}", code, playerName.value());
    }

    private void createRoom(final Room room, final String code) {
        final String envelope = buildEnvelope(ROOM_CREATE, code, Map.of("joinCode", code));
        final Long result = stringRedisTemplate.execute(
                createRoomScript,
                List.of(
                        META_KEY.formatted(code),
                        PLAYERS_KEY.formatted(code),
                        JOINCODE_KEY.formatted(code)
                ),
                room.getHost().getName().value(),
                room.getRoomState().name(),
                "NONE",
                String.valueOf(System.currentTimeMillis()),
                String.valueOf(MAX_PLAYERS),
                String.valueOf(ttl.toSeconds()),
                envelope
        );

        if (result != null && result == DUPLICATE_JOINCODE) {
            log.warn("방 생성 실패 - 중복 joinCode: {}", code);
        }
        room.getPlayers().forEach(player -> writePlayer(code, player));
        log.info("방 생성 완료: joinCode={}", code);
    }

    private void writePlayer(final String code, final Player player) {
        final String json = mapper.toJson(player);
        redisTemplate.opsForHash().put(PLAYER_DATA_KEY.formatted(code), player.getName().value(), json);
        redisTemplate.opsForHash().put(READY_KEY.formatted(code), player.getName().value(),
                String.valueOf(player.getIsReady()));
    }

    private void updatePlayerDataReady(final String code, final PlayerName playerName, final boolean ready) {
        final Object json = redisTemplate.opsForHash()
                .get(PLAYER_DATA_KEY.formatted(code), playerName.value());
        if (json == null) {
            return;
        }
        try {
            final RedisPlayerData data = objectMapper.readValue((String) json, RedisPlayerData.class);
            final RedisPlayerData updated = new RedisPlayerData(
                    data.playerName(), data.playerType(), data.menuName(),
                    data.menuCategoryImageUrl(), data.temperature(), ready,
                    data.colorIndex(), data.probability()
            );
            redisTemplate.opsForHash().put(PLAYER_DATA_KEY.formatted(code), playerName.value(),
                    objectMapper.writeValueAsString(updated));
        } catch (JsonProcessingException e) {
            log.error("플레이어 ready 갱신 실패: player={}", playerName.value(), e);
            throw new IllegalStateException("플레이어 ready 갱신 실패", e);
        }
    }

    private void validateEnterResult(final Long result, final String playerName) {
        if (result == null) {
            throw new IllegalStateException("Lua enter_room 실행 실패: playerName=" + playerName);
        }
        if (result == OK) {
            return;
        }
        if (result == FULL) {
            throw new InvalidStateException(RoomErrorCode.ROOM_FULL,
                    "방이 가득 찼습니다: playerName=" + playerName);
        }
        if (result == DUPLICATE_NAME) {
            throw new InvalidStateException(RoomErrorCode.DUPLICATE_PLAYER_NAME,
                    "중복된 플레이어 이름: " + playerName);
        }
        if (result == ROOM_NOT_FOUND) {
            throw new NotExistElementException(GlobalErrorCode.NOT_EXIST,
                    "방이 존재하지 않습니다: playerName=" + playerName);
        }
        throw new IllegalStateException("알 수 없는 Lua 반환값: " + result);
    }

    private String buildEnvelope(final String eventType, final String joinCode, final Map<String, Object> payload) {
        try {
            final String payloadJson = objectMapper.writeValueAsString(payload);
            return envelopeSerializer.toJson(new PubSubEnvelope(
                    eventType, joinCode, payloadJson, System.currentTimeMillis(), selfInstanceId
            ));
        } catch (JsonProcessingException e) {
            log.error("Envelope 페이로드 직렬화 실패: eventType={}", eventType, e);
            throw new IllegalStateException("Envelope 직렬화 실패", e);
        }
    }
}
