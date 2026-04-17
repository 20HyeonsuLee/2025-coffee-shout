package coffeeshout.room.infra.redis;

import coffeeshout.global.luacommand.annotation.RedisTransactional;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@link RoomLuaCommands}를 atomic composite Lua 단위로 묶는 경계.
 *
 * <p>상위 {@code RoomService}가 이미 {@link RedisTransactional} 스코프를 열어둔 production 경로에서는
 * nested 로 동작해 outer 스코프에 명령이 합류된다. 테스트나 다른 단독 호출 경로에서는 여기가 outer
 * 스코프가 되어 메서드 종료 시점에 composite Lua 가 실행된다.
 *
 * <p>AOP 프록시 경유가 필요하므로 repo와 별개의 빈으로 둔다.
 */
@Component
public class RoomLuaOperations {

    private final RoomLuaCommands commands;

    public RoomLuaOperations(final RoomLuaCommands commands) {
        this.commands = commands;
    }

    @RedisTransactional
    public void joinRoom(
            final String joinCode,
            final String playerName,
            final int maxPlayers,
            final String envelope,
            final String playerJson,
            final String readyValue
    ) {
        commands.checkRoomExists(joinCode);
        commands.checkRoomReady(joinCode);
        commands.checkCapacity(joinCode, maxPlayers);
        commands.checkNotDuplicate(joinCode, playerName);
        commands.addPlayerAndPublish(joinCode, playerName, envelope);
        commands.writePlayerData(joinCode, playerName, playerJson);
        commands.writePlayerReady(joinCode, playerName, readyValue);
    }

    @RedisTransactional
    public void toggleReady(
            final String joinCode, final String playerName, final String ready, final String envelope
    ) {
        commands.checkPlayerExists(joinCode, playerName);
        commands.writeReadyAndPublish(joinCode, playerName, ready, envelope);
    }

    @RedisTransactional
    public void createRoom(
            final String joinCode,
            final String hostName,
            final String state,
            final String gameType,
            final long createdAt,
            final int maxPlayers,
            final long ttlSeconds,
            final String envelope,
            final List<PlayerRecord> additionalPlayers
    ) {
        commands.checkRoomNotExists(joinCode);
        commands.writeRoomMetaAndPublish(
                joinCode, hostName, state, gameType, createdAt, maxPlayers, ttlSeconds, envelope
        );
        for (final PlayerRecord player : additionalPlayers) {
            commands.writePlayerData(joinCode, player.name(), player.json());
            commands.writePlayerReady(joinCode, player.name(), player.readyValue());
        }
    }

    @RedisTransactional
    public void updateState(
            final String joinCode, final String state, final List<PlayerRecord> players
    ) {
        commands.updateRoomState(joinCode, state);
        for (final PlayerRecord player : players) {
            commands.writePlayerData(joinCode, player.name(), player.json());
            commands.writePlayerReady(joinCode, player.name(), player.readyValue());
        }
    }

    @RedisTransactional
    public void updatePositions(
            final String joinCode, final long ttlSeconds, final String envelope, final List<String> positionPairs
    ) {
        commands.writePositionsAndPublish(joinCode, ttlSeconds, envelope, positionPairs);
    }

    @RedisTransactional
    public void removePlayer(final String joinCode, final String playerName, final String envelope) {
        commands.checkPlayerExists(joinCode, playerName);
        commands.removePlayerCore(joinCode, playerName, envelope);
        commands.deletePlayerData(joinCode, playerName);
    }

    public record PlayerRecord(String name, String json, String readyValue) {
    }
}
