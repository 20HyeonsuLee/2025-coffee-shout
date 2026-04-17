package coffeeshout.global.luacommand;

import coffeeshout.global.luacommand.annotation.RedisTransactional;
import org.springframework.stereotype.Service;

/**
 * {@link CoffeeShoutRoomCommandsPoC}의 검증/쓰기 명령을 트랜잭션 안에서 조합.
 * AOP 프록시 경유를 위해 명령 컴포넌트와 서비스 컴포넌트를 분리한다.
 */
@Service
public class CoffeeShoutRoomServicePoC {

    private final CoffeeShoutRoomCommandsPoC commands;

    public CoffeeShoutRoomServicePoC(final CoffeeShoutRoomCommandsPoC commands) {
        this.commands = commands;
    }

    @RedisTransactional
    public void joinGuest(
            final String joinCode,
            final String playerName,
            final int maxPlayers,
            final String envelope
    ) {
        commands.checkRoomExists(joinCode);
        commands.checkCapacity(joinCode, maxPlayers);
        commands.checkNotDuplicate(joinCode, playerName);
        commands.addPlayer(joinCode, playerName, envelope);
    }
}
