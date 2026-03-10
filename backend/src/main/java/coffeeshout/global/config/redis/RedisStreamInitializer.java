package coffeeshout.global.config.redis;

import coffeeshout.global.config.properties.RedisStreamProperties;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RedisStreamInitializer {

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisStreamProperties streamProperties;

    public void initialize() {
        createStreamIfAbsent(streamProperties.roomJoinKey());
        createStreamIfAbsent(streamProperties.cardGameSelectKey());
        createStreamIfAbsent(streamProperties.racingGameKey());
        createConsumerGroupIfAbsent(streamProperties.racingGameKey(), "racing-game-group");
    }

    private void createStreamIfAbsent(final String key) {
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(key))) {
            return;
        }
        stringRedisTemplate.opsForStream().add(key, Map.of("_init", "_init"));
        stringRedisTemplate.opsForStream().trim(key, 0);
    }

    private void createConsumerGroupIfAbsent(final String key, final String group) {
        try {
            stringRedisTemplate.opsForStream().createGroup(key, group);
        } catch (final Exception e) {
            // BUSYGROUP: 이미 존재하는 그룹이면 무시
        }
    }
}
