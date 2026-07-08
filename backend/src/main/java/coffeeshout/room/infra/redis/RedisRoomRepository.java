package coffeeshout.room.infra.redis;

import coffeeshout.global.messaging.PubSubEnvelope;
import coffeeshout.global.messaging.PubSubEnvelopeSerializer;
import coffeeshout.global.metric.PubSubMetricService;
import io.micrometer.observation.annotation.Observed;
import coffeeshout.room.domain.JoinCode;
import coffeeshout.room.domain.Room;
import coffeeshout.room.domain.player.Player;
import coffeeshout.room.domain.player.PlayerName;
import coffeeshout.room.domain.repository.RoomRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * Redis 기반 RoomRepository 구현체.
 *
 * <p>원자성은 서비스 레이어의 Redisson {@code RLock}이 보장한다.
 * 이 클래스는 락 안에서 호출되는 단순 Redis 읽기/쓰기 + Pub/Sub 발행만 담당.
 */
@Slf4j
@Repository
public class RedisRoomRepository implements RoomRepository {

    private static final String META_KEY = "room:%s:meta";
    private static final String PLAYERS_KEY = "room:%s:players";
    private static final String READY_KEY = "room:%s:ready";
    private static final String PLAYER_DATA_KEY = "room:%s:player_data";
    private static final String POSITIONS_KEY = "room:%s:positions";
    private static final String VERSION_KEY = "room:%s:version";
    private static final String JOINCODE_KEY = "joincode:%s";
    private static final String CHANNEL = "coffeeshout:events";
    private static final int MAX_PLAYERS = 9;
    private static final String PLAYER_LIST_UPDATE = "PLAYER_LIST_UPDATE";
    private static final String PLAYER_READY = "PLAYER_READY";
    private static final String ROOM_CREATE = "ROOM_CREATE";
    private static final String RACING_POSITIONS = "RACING_POSITIONS";

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisTemplate<String, String> redisTemplate;
    private final PubSubEnvelopeSerializer envelopeSerializer;
    private final ObjectMapper objectMapper;
    private final RedisRoomMapper mapper;
    private final String selfInstanceId;
    private final PubSubMetricService pubSubMetric;

    @Value("${room.removalDelay}")
    private Duration ttl;

    public RedisRoomRepository(
            final StringRedisTemplate stringRedisTemplate,
            final RedisTemplate<String, String> redisTemplate,
            final PubSubEnvelopeSerializer envelopeSerializer,
            final ObjectMapper objectMapper,
            final RedisRoomMapper mapper,
            final @Qualifier("selfInstanceId") String selfInstanceId,
            final PubSubMetricService pubSubMetric
    ) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.redisTemplate = redisTemplate;
        this.envelopeSerializer = envelopeSerializer;
        this.objectMapper = objectMapper;
        this.mapper = mapper;
        this.selfInstanceId = selfInstanceId;
        this.pubSubMetric = pubSubMetric;
    }

    @Override
    @Observed(name = "room.repo.findByJoinCode")
    public Optional<Room> findByJoinCode(final JoinCode joinCode) {
        final Map<Object, Object> meta = redisTemplate.opsForHash()
                .entries(META_KEY.formatted(joinCode.getValue()));
        if (meta.isEmpty()) {
            return Optional.empty();
        }
        final Map<Object, Object> playerDataMap = redisTemplate.opsForHash()
                .entries(PLAYER_DATA_KEY.formatted(joinCode.getValue()));
        final Map<Object, Object> readyMap = redisTemplate.opsForHash()
                .entries(READY_KEY.formatted(joinCode.getValue()));
        return Optional.of(mapper.toRoom(joinCode, meta, playerDataMap, readyMap));
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
                VERSION_KEY.formatted(code),
                JOINCODE_KEY.formatted(code)
        ));
        log.info("방 삭제 완료: joinCode={}", code);
    }

    @Override
    @Observed(name = "room.repo.addPlayer")
    public void addPlayer(final JoinCode joinCode, final Player player) {
        final String code = joinCode.getValue();
        stringRedisTemplate.opsForSet().add(PLAYERS_KEY.formatted(code), player.getName().value());
        writePlayer(code, player);
        publish(PLAYER_LIST_UPDATE, code, Map.of("joinCode", code));
        log.debug("플레이어 추가 완료: joinCode={}, player={}", code, player.getName().value());
    }

    @Override
    @Observed(name = "room.repo.updatePlayerReady")
    public void updatePlayerReady(final JoinCode joinCode, final PlayerName playerName, final boolean ready) {
        final String code = joinCode.getValue();
        redisTemplate.opsForHash().put(READY_KEY.formatted(code), playerName.value(), String.valueOf(ready));
        publish(PLAYER_READY, code, Map.of("playerName", playerName.value(), "isReady", ready));
    }

    @Override
    @Observed(name = "room.repo.removePlayer")
    public void removePlayer(final JoinCode joinCode, final PlayerName playerName) {
        final String code = joinCode.getValue();
        stringRedisTemplate.opsForSet().remove(PLAYERS_KEY.formatted(code), playerName.value());
        redisTemplate.opsForHash().delete(READY_KEY.formatted(code), playerName.value());
        redisTemplate.opsForHash().delete(POSITIONS_KEY.formatted(code), playerName.value());
        redisTemplate.opsForHash().delete(PLAYER_DATA_KEY.formatted(code), playerName.value());
        publish(PLAYER_LIST_UPDATE, code, Map.of("joinCode", code, "playerName", playerName.value()));
        log.debug("플레이어 제거 완료: joinCode={}, player={}", code, playerName.value());
    }

    @Override
    public void updatePositions(final JoinCode joinCode, final Map<PlayerName, Integer> positions) {
        if (positions.isEmpty()) {
            return;
        }
        final String code = joinCode.getValue();
        final Map<String, Integer> snapshot = toNameKeyedSnapshot(positions);
        snapshot.forEach((name, position) ->
                redisTemplate.opsForHash().put(POSITIONS_KEY.formatted(code), name, String.valueOf(position))
        );
        redisTemplate.expire(POSITIONS_KEY.formatted(code), ttl);
        publish(RACING_POSITIONS, code, Map.of("joinCode", code, "positions", snapshot));
    }

    private void createRoom(final Room room, final String code) {
        redisTemplate.opsForHash().putAll(META_KEY.formatted(code), Map.of(
                "hostName", room.getHost().getName().value(),
                "state", room.getRoomState().name(),
                "gameType", "NONE",
                "createdAt", String.valueOf(System.currentTimeMillis()),
                "maxPlayers", String.valueOf(MAX_PLAYERS)
        ));
        redisTemplate.expire(META_KEY.formatted(code), ttl);
        stringRedisTemplate.opsForSet().add(PLAYERS_KEY.formatted(code), room.getHost().getName().value());
        redisTemplate.expire(PLAYERS_KEY.formatted(code), ttl);
        redisTemplate.expire(JOINCODE_KEY.formatted(code), ttl);
        room.getPlayers().forEach(player -> writePlayer(code, player));
        publish(ROOM_CREATE, code, Map.of("joinCode", code));
        log.info("방 생성 완료: joinCode={}", code);
    }

    private void writePlayer(final String code, final Player player) {
        redisTemplate.opsForHash().put(
                PLAYER_DATA_KEY.formatted(code), player.getName().value(), mapper.toJson(player));
        redisTemplate.opsForHash().put(
                READY_KEY.formatted(code), player.getName().value(), String.valueOf(player.getIsReady()));
    }

    private Map<String, Integer> toNameKeyedSnapshot(final Map<PlayerName, Integer> positions) {
        return positions.entrySet().stream()
                .collect(Collectors.toMap(
                        entry -> entry.getKey().value(),
                        Map.Entry::getValue,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    private void publish(final String eventType, final String joinCode, final Map<String, Object> payload) {
        final long version = nextVersion(joinCode);
        final String eventId = UUID.randomUUID().toString();
        final String envelope = buildEnvelope(eventId, eventType, joinCode, payload, version);
        stringRedisTemplate.convertAndSend(CHANNEL, envelope);
        pubSubMetric.recordPublish(eventType);
    }

    private long nextVersion(final String joinCode) {
        final String versionKey = VERSION_KEY.formatted(joinCode);
        final Long version = stringRedisTemplate.opsForValue().increment(versionKey);
        if (version == null) {
            throw new IllegalStateException("roomVersion 증가 실패: joinCode=" + joinCode);
        }
        stringRedisTemplate.expire(versionKey, ttl);
        return version;
    }

    private String buildEnvelope(
            final String eventId,
            final String eventType,
            final String joinCode,
            final Map<String, Object> payload,
            final long version
    ) {
        try {
            final Map<String, Object> versionedPayload = new LinkedHashMap<>(payload);
            versionedPayload.put("version", version);
            versionedPayload.put("eventId", eventId);
            final String payloadJson = objectMapper.writeValueAsString(versionedPayload);
            return envelopeSerializer.toJson(new PubSubEnvelope(
                    eventId, eventType, joinCode, payloadJson, version, System.currentTimeMillis(), selfInstanceId
            ));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Envelope 직렬화 실패", e);
        }
    }
}
