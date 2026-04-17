package coffeeshout.room.infra.redis;

import coffeeshout.global.luacommand.annotation.LuaCommand;
import coffeeshout.global.luacommand.annotation.ReturnCode;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 방 도메인의 Redis 작업을 Lua 명령 단위로 선언.
 *
 * <p>검증({@code validation=true}) 메서드는 조건 체크만, 쓰기 메서드는 SADD/HSET/EXPIRE/PUBLISH 등을 묶어 실행.
 * 여러 명령을 {@link RoomLuaOperations}의 {@code @RedisTransactional} 메서드에서 조합하면
 * 라이브러리가 단일 composite Lua 로 합성해 원자 실행한다.
 *
 * <p>모든 쓰기 명령은 성공 시 {@code PUBLISH 'coffeeshout:events' <envelope>} 를 포함해
 * 다른 WAS 로 상태 변경을 전파한다.
 */
@Component
public class RoomLuaCommands {

    @LuaCommand(
            validation = true,
            script = "if redis.call('EXISTS', KEYS[1]) == 0 then return -3 end",
            keys = {"room:#{#joinCode}:meta"},
            returns = {
                    @ReturnCode(value = -3, throwsException = RoomLuaException.RoomNotFound.class,
                            message = "방이 존재하지 않습니다")
            }
    )
    public void checkRoomExists(final String joinCode) {
    }

    @LuaCommand(
            validation = true,
            script = "if redis.call('EXISTS', KEYS[1]) == 1 then return -1 end",
            keys = {"room:#{#joinCode}:meta"},
            returns = {
                    @ReturnCode(value = -1, throwsException = RoomLuaException.RoomAlreadyExists.class,
                            message = "방이 이미 존재합니다")
            }
    )
    public void checkRoomNotExists(final String joinCode) {
    }

    @LuaCommand(
            validation = true,
            script = "if redis.call('HGET', KEYS[1], 'state') ~= 'READY' then return -4 end",
            keys = {"room:#{#joinCode}:meta"},
            returns = {
                    @ReturnCode(value = -4, throwsException = RoomLuaException.RoomNotReady.class,
                            message = "READY 상태에서만 참여 가능합니다")
            }
    )
    public void checkRoomReady(final String joinCode) {
    }

    @LuaCommand(
            validation = true,
            script = "if redis.call('SCARD', KEYS[1]) >= tonumber(ARGV[1]) then return -1 end",
            keys = {"room:#{#joinCode}:players"},
            args = {"#{#maxPlayers}"},
            returns = {
                    @ReturnCode(value = -1, throwsException = RoomLuaException.RoomFull.class,
                            message = "방이 가득 찼습니다")
            }
    )
    public void checkCapacity(final String joinCode, final int maxPlayers) {
    }

    @LuaCommand(
            validation = true,
            script = "if redis.call('SISMEMBER', KEYS[1], ARGV[1]) == 1 then return -2 end",
            keys = {"room:#{#joinCode}:players"},
            args = {"#{#playerName}"},
            returns = {
                    @ReturnCode(value = -2, throwsException = RoomLuaException.DuplicateName.class,
                            message = "중복된 플레이어 이름")
            }
    )
    public void checkNotDuplicate(final String joinCode, final String playerName) {
    }

    @LuaCommand(
            validation = true,
            script = "if redis.call('SISMEMBER', KEYS[1], ARGV[1]) == 0 then return -1 end",
            keys = {"room:#{#joinCode}:players"},
            args = {"#{#playerName}"},
            returns = {
                    @ReturnCode(value = -1, throwsException = RoomLuaException.PlayerNotFound.class,
                            message = "플레이어가 존재하지 않습니다")
            }
    )
    public void checkPlayerExists(final String joinCode, final String playerName) {
    }

    @LuaCommand(
            script = """
                    redis.call('SADD', KEYS[1], ARGV[1])
                    redis.call('PUBLISH', 'coffeeshout:events', ARGV[2])
                    """,
            keys = {"room:#{#joinCode}:players"},
            args = {"#{#playerName}", "#{#envelope}"}
    )
    public void addPlayerAndPublish(final String joinCode, final String playerName, final String envelope) {
    }

    @LuaCommand(
            script = """
                    redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
                    redis.call('PUBLISH', 'coffeeshout:events', ARGV[3])
                    """,
            keys = {"room:#{#joinCode}:ready"},
            args = {"#{#playerName}", "#{#ready}", "#{#envelope}"}
    )
    public void writeReadyAndPublish(
            final String joinCode, final String playerName, final String ready, final String envelope
    ) {
    }

    @LuaCommand(
            script = """
                    redis.call('HSET', KEYS[1],
                        'hostName', ARGV[1],
                        'state', ARGV[2],
                        'gameType', ARGV[3],
                        'createdAt', ARGV[4],
                        'maxPlayers', ARGV[5]
                    )
                    redis.call('EXPIRE', KEYS[1], ARGV[6])
                    redis.call('SADD', KEYS[2], ARGV[1])
                    redis.call('EXPIRE', KEYS[2], ARGV[6])
                    redis.call('EXPIRE', KEYS[3], ARGV[6])
                    redis.call('PUBLISH', 'coffeeshout:events', ARGV[7])
                    """,
            keys = {"room:#{#joinCode}:meta", "room:#{#joinCode}:players", "joincode:#{#joinCode}"},
            args = {"#{#hostName}", "#{#state}", "#{#gameType}", "#{#createdAt}",
                    "#{#maxPlayers}", "#{#ttlSeconds}", "#{#envelope}"}
    )
    public void writeRoomMetaAndPublish(
            final String joinCode,
            final String hostName,
            final String state,
            final String gameType,
            final long createdAt,
            final int maxPlayers,
            final long ttlSeconds,
            final String envelope
    ) {
    }

    @LuaCommand(
            script = """
                    for i = 3, #ARGV, 2 do
                        redis.call('HSET', KEYS[1], ARGV[i], ARGV[i + 1])
                    end
                    redis.call('EXPIRE', KEYS[1], ARGV[1])
                    redis.call('PUBLISH', 'coffeeshout:events', ARGV[2])
                    """,
            keys = {"room:#{#joinCode}:positions"},
            args = {"#{#ttlSeconds}", "#{#envelope}", "#{#positionPairs}"}
    )
    public void writePositionsAndPublish(
            final String joinCode,
            final long ttlSeconds,
            final String envelope,
            final List<String> positionPairs
    ) {
    }

    @LuaCommand(
            script = """
                    redis.call('SREM', KEYS[1], ARGV[1])
                    redis.call('HDEL', KEYS[2], ARGV[1])
                    redis.call('HDEL', KEYS[3], ARGV[1])
                    redis.call('PUBLISH', 'coffeeshout:events', ARGV[2])
                    """,
            keys = {
                    "room:#{#joinCode}:players",
                    "room:#{#joinCode}:ready",
                    "room:#{#joinCode}:positions"
            },
            args = {"#{#playerName}", "#{#envelope}"}
    )
    public void removePlayerCore(final String joinCode, final String playerName, final String envelope) {
    }

    @LuaCommand(
            script = "redis.call('HDEL', KEYS[1], ARGV[1])",
            keys = {"room:#{#joinCode}:player_data"},
            args = {"#{#playerName}"}
    )
    public void deletePlayerData(final String joinCode, final String playerName) {
    }

    @LuaCommand(
            script = "redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])",
            keys = {"room:#{#joinCode}:player_data"},
            args = {"#{#playerName}", "#{#json}"}
    )
    public void writePlayerData(final String joinCode, final String playerName, final String json) {
    }

    @LuaCommand(
            script = "redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])",
            keys = {"room:#{#joinCode}:ready"},
            args = {"#{#playerName}", "#{#ready}"}
    )
    public void writePlayerReady(final String joinCode, final String playerName, final String ready) {
    }

    @LuaCommand(
            script = "redis.call('HSET', KEYS[1], 'state', ARGV[1])",
            keys = {"room:#{#joinCode}:meta"},
            args = {"#{#state}"}
    )
    public void updateRoomState(final String joinCode, final String state) {
    }
}
