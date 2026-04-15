package coffeeshout.global.websocket;

import static org.springframework.util.Assert.isTrue;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis Hash 기반 StompSessionManager.
 * 비-test 환경에서 메모리 기반 StompSessionManager를 대체한다.
 * StompSessionManager를 상속하여 동일 타입으로 주입 가능.
 */
@Slf4j
@Component
@Primary
@Profile("!test")
public class RedisStompSessionManager extends StompSessionManager {

    private static final String PLAYER_TO_SESSION = "session:player-to-session";
    private static final String SESSION_TO_PLAYER = "session:session-to-player";
    private static final String PLAYER_KEY_DELIMITER = ":";
    private static final int EXPECTED_PLAYER_KEY_PARTS = 2;

    private final StringRedisTemplate stringRedisTemplate;

    // 중복 처리 방지용 (WAS 로컬, 세션 수명과 동일하므로 분산 불필요)
    private final Set<String> processedDisconnections = ConcurrentHashMap.newKeySet();

    public RedisStompSessionManager(final StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public void registerPlayerSession(
            @NonNull final String joinCode,
            @NonNull final String playerName,
            @NonNull final String sessionId
    ) {
        final String playerKey = createPlayerKey(joinCode, playerName);

        final String oldSessionId = (String) stringRedisTemplate.opsForHash()
                .get(PLAYER_TO_SESSION, playerKey);
        if (oldSessionId != null) {
            log.info("기존 플레이어 세션 정리: playerKey={}, oldSessionId={}", playerKey, oldSessionId);
            stringRedisTemplate.opsForHash().delete(SESSION_TO_PLAYER, oldSessionId);
        }

        stringRedisTemplate.opsForHash().put(PLAYER_TO_SESSION, playerKey, sessionId);
        stringRedisTemplate.opsForHash().put(SESSION_TO_PLAYER, sessionId, playerKey);
        log.info("플레이어 세션 매핑 등록: playerKey={}, sessionId={}", playerKey, sessionId);
    }

    @Override
    public void registerPlayerSessionInternal(
            @NonNull final String playerKey,
            @NonNull final String sessionId
    ) {
        validatePlayerKey(playerKey);

        final String oldSessionId = (String) stringRedisTemplate.opsForHash()
                .get(PLAYER_TO_SESSION, playerKey);
        if (oldSessionId != null) {
            log.info("기존 플레이어 세션 정리: playerKey={}, oldSessionId={}", playerKey, oldSessionId);
            stringRedisTemplate.opsForHash().delete(SESSION_TO_PLAYER, oldSessionId);
        }

        stringRedisTemplate.opsForHash().put(PLAYER_TO_SESSION, playerKey, sessionId);
        stringRedisTemplate.opsForHash().put(SESSION_TO_PLAYER, sessionId, playerKey);
        log.info("플레이어 세션 매핑 등록: playerKey={}, sessionId={}", playerKey, sessionId);
    }

    @Override
    public boolean hasSessionId(@NonNull final String joinCode, @NonNull final String playerName) {
        return stringRedisTemplate.opsForHash()
                .hasKey(PLAYER_TO_SESSION, createPlayerKey(joinCode, playerName));
    }

    @Override
    public boolean hasPlayerKeyInternal(@NonNull final String playerKey) {
        return stringRedisTemplate.opsForHash().hasKey(PLAYER_TO_SESSION, playerKey);
    }

    @Override
    public String getSessionId(@NonNull final String joinCode, @NonNull final String playerName) {
        final String playerKey = createPlayerKey(joinCode, playerName);
        final String sessionId = (String) stringRedisTemplate.opsForHash().get(PLAYER_TO_SESSION, playerKey);

        isTrue(sessionId != null,
                "플레이어 세션이 존재하지 않습니다: joinCode=%s, playerName=%s".formatted(joinCode, playerName));
        return sessionId;
    }

    @Override
    public boolean hasPlayerKey(@NonNull final String sessionId) {
        return stringRedisTemplate.opsForHash().hasKey(SESSION_TO_PLAYER, sessionId);
    }

    @Override
    public String getPlayerKey(@NonNull final String sessionId) {
        final String playerKey = (String) stringRedisTemplate.opsForHash().get(SESSION_TO_PLAYER, sessionId);

        isTrue(playerKey != null, "세션 ID가 존재하지 않습니다: sessionId=%s".formatted(sessionId));
        return playerKey;
    }

    @Override
    public String extractJoinCode(@NonNull final String playerKey) {
        validatePlayerKey(playerKey);
        return playerKey.split(PLAYER_KEY_DELIMITER)[0];
    }

    @Override
    public String extractPlayerName(@NonNull final String playerKey) {
        validatePlayerKey(playerKey);
        return playerKey.split(PLAYER_KEY_DELIMITER)[1];
    }

    @Override
    public void removeSession(@NonNull final String sessionId) {
        final String playerKey = (String) stringRedisTemplate.opsForHash().get(SESSION_TO_PLAYER, sessionId);
        if (playerKey != null) {
            stringRedisTemplate.opsForHash().delete(PLAYER_TO_SESSION, playerKey);
            log.info("세션 매핑 제거: playerKey={}, sessionId={}", playerKey, sessionId);
        }
        stringRedisTemplate.opsForHash().delete(SESSION_TO_PLAYER, sessionId);
        processedDisconnections.remove(sessionId);
    }

    @Override
    public void removeSessionInternal(@NonNull final String sessionId) {
        removeSession(sessionId);
    }

    @Override
    public boolean isDisconnectionProcessed(@NonNull final String sessionId) {
        return !processedDisconnections.add(sessionId);
    }

    @Override
    public long getConnectedPlayerCountByJoinCode(@NonNull final String joinCode) {
        return stringRedisTemplate.opsForHash().keys(PLAYER_TO_SESSION).stream()
                .filter(key -> ((String) key).startsWith(joinCode + PLAYER_KEY_DELIMITER))
                .count();
    }

    @Override
    public int getTotalConnectedClientCount() {
        final Long size = stringRedisTemplate.opsForHash().size(SESSION_TO_PLAYER);
        return size == null ? 0 : size.intValue();
    }

    @Override
    public String createPlayerKey(@NonNull final String joinCode, @NonNull final String playerName) {
        if (joinCode.contains(PLAYER_KEY_DELIMITER) || playerName.contains(PLAYER_KEY_DELIMITER)) {
            throw new IllegalArgumentException(
                    "joinCode와 playerName에 구분자('" + PLAYER_KEY_DELIMITER + "')가 포함될 수 없습니다");
        }
        return joinCode + PLAYER_KEY_DELIMITER + playerName;
    }

    @Override
    public boolean isValidPlayerKey(final String playerKey) {
        if (playerKey == null || !playerKey.contains(PLAYER_KEY_DELIMITER)) {
            return false;
        }
        final String[] parts = playerKey.split(PLAYER_KEY_DELIMITER);
        return parts.length == EXPECTED_PLAYER_KEY_PARTS
                && !parts[0].isEmpty() && !parts[1].isEmpty();
    }

    private void validatePlayerKey(@NonNull final String playerKey) {
        if (!playerKey.contains(PLAYER_KEY_DELIMITER)) {
            throw new IllegalArgumentException(
                    "플레이어 키에 구분자('" + PLAYER_KEY_DELIMITER + "')가 없습니다: " + playerKey);
        }
        final String[] parts = playerKey.split(PLAYER_KEY_DELIMITER);
        if (parts.length != EXPECTED_PLAYER_KEY_PARTS) {
            throw new IllegalArgumentException("플레이어 키 형식이 잘못됨: " + playerKey);
        }
        if (parts[0].isEmpty() || parts[1].isEmpty()) {
            throw new IllegalArgumentException("joinCode 또는 playerName이 비어있습니다: " + playerKey);
        }
    }
}
