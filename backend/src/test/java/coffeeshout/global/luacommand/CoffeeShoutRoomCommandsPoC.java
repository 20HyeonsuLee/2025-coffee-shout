package coffeeshout.global.luacommand;

import coffeeshout.global.luacommand.annotation.LuaCommand;
import coffeeshout.global.luacommand.annotation.ReturnCode;
import org.springframework.stereotype.Component;

/**
 * Coffee-Shout joinGuest 시나리오를 라이브러리로 구현한 PoC.
 *
 * <p>기존 {@code enter_room.lua}가 하던 검증(정원/중복/방존재) + 쓰기(SADD/PUBLISH)를
 * <b>4개의 메서드로 분리</b>. 조합은 별도 서비스({@code CoffeeShoutRoomServicePoC})에서.
 */
@Component
public class CoffeeShoutRoomCommandsPoC {

    public static class RoomFullException extends RuntimeException {
        public RoomFullException(final String message) { super(message); }
    }

    public static class DuplicateNameException extends RuntimeException {
        public DuplicateNameException(final String message) { super(message); }
    }

    public static class RoomNotFoundException extends RuntimeException {
        public RoomNotFoundException(final String message) { super(message); }
    }

    // ---- 검증 명령 ----

    @LuaCommand(
            validation = true,
            script = "if redis.call('EXISTS', KEYS[1]) == 0 then return -1 end",
            keys = {"room:#{#a0}:meta"},
            returns = {
                    @ReturnCode(value = -1, throwsException = RoomNotFoundException.class, message = "방이 존재하지 않습니다")
            }
    )
    public void checkRoomExists(final String joinCode) {
    }

    @LuaCommand(
            validation = true,
            script = "if redis.call('SCARD', KEYS[1]) >= tonumber(ARGV[1]) then return -1 end",
            keys = {"room:#{#a0}:players"},
            args = {"#{#a1}"},
            returns = {
                    @ReturnCode(value = -1, throwsException = RoomFullException.class, message = "방이 가득 찼습니다")
            }
    )
    public void checkCapacity(final String joinCode, final int maxPlayers) {
    }

    @LuaCommand(
            validation = true,
            script = "if redis.call('SISMEMBER', KEYS[1], ARGV[1]) == 1 then return -1 end",
            keys = {"room:#{#a0}:players"},
            args = {"#{#a1}"},
            returns = {
                    @ReturnCode(value = -1, throwsException = DuplicateNameException.class, message = "중복된 플레이어 이름")
            }
    )
    public void checkNotDuplicate(final String joinCode, final String playerName) {
    }

    // ---- 쓰기 명령 ----

    @LuaCommand(
            script = """
                    redis.call('SADD', KEYS[1], ARGV[1])
                    redis.call('PUBLISH', 'coffeeshout:events', ARGV[2])
                    """,
            keys = {"room:#{#a0}:players"},
            args = {"#{#a1}", "#{#a2}"}
    )
    public void addPlayer(final String joinCode, final String playerName, final String envelope) {
    }
}
