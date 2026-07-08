package coffeeshout.room.infra.redis;

import coffeeshout.room.domain.JoinCode;
import coffeeshout.room.domain.Room;
import coffeeshout.room.domain.RoomState;
import coffeeshout.room.domain.player.Player;
import coffeeshout.room.domain.player.PlayerName;
import coffeeshout.room.domain.player.PlayerType;
import coffeeshout.room.domain.player.Players;
import coffeeshout.room.domain.roulette.Probability;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Redis Hash ↔ Room/Player 매핑 담당.
 * 도메인 재조립 팩터리 호출은 이 클래스에만 존재한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisRoomMapper {

    private final ObjectMapper objectMapper;

    public Room toRoom(
            final JoinCode joinCode,
            final Map<Object, Object> meta,
            final Map<Object, Object> playerDataMap,
            final Map<Object, Object> readyMap
    ) {
        final String hostName = (String) meta.get("hostName");
        final RoomState roomState = RoomState.valueOf((String) meta.getOrDefault("state", "READY"));

        final List<Player> players = playerDataMap.values().stream()
                .map(json -> toPlayer(fromJson((String) json), readyMap))
                .toList();

        final Player host = players.stream()
                .filter(p -> p.getName().value().equals(hostName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("호스트를 찾을 수 없습니다: " + hostName));

        final Players collection = Players.ofStored(joinCode.getValue(), players);
        return Room.ofStored(joinCode, host, collection, roomState);
    }

    public String toJson(final Player player) {
        try {
            return objectMapper.writeValueAsString(RedisPlayerData.from(player));
        } catch (JsonProcessingException e) {
            log.error("플레이어 데이터 직렬화 실패: player={}", player.getName().value(), e);
            throw new IllegalStateException("플레이어 데이터 직렬화 실패", e);
        }
    }

    private RedisPlayerData fromJson(final String json) {
        try {
            return objectMapper.readValue(json, RedisPlayerData.class);
        } catch (JsonProcessingException e) {
            log.error("플레이어 데이터 역직렬화 실패: json={}", json, e);
            throw new IllegalStateException("플레이어 데이터 역직렬화 실패", e);
        }
    }

    private Player toPlayer(final RedisPlayerData data, final Map<Object, Object> readyMap) {
        final Object readyRaw = readyMap.get(data.playerName());
        final boolean isReady = readyRaw != null && Boolean.parseBoolean(readyRaw.toString());
        return Player.ofStored(
                new PlayerName(data.playerName()),
                PlayerType.valueOf(data.playerType()),
                isReady,
                data.colorIndex(),
                new Probability(data.probability())
        );
    }
}
