package coffeeshout.room.infra;

import coffeeshout.room.domain.JoinCode;
import coffeeshout.room.domain.repository.JoinCodeRepository;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * Redis SETNX 기반 JoinCode 유일성 보장 구현체.
 * Redis SET NX EX API로 입장 코드 선점을 원자 처리한다.
 */
@Slf4j
@Repository
public class RedisJoinCodeRepository implements JoinCodeRepository {

    private static final String KEY_PREFIX = "joincode:";

    private final StringRedisTemplate stringRedisTemplate;

    @Value("${room.removalDelay}")
    private Duration ttl;

    public RedisJoinCodeRepository(final StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean save(final JoinCode joinCode) {
        final String key = KEY_PREFIX + joinCode.getValue();
        final Boolean result = stringRedisTemplate.opsForValue().setIfAbsent(key, "claimed", ttl);
        final boolean acquired = Boolean.TRUE.equals(result);
        log.debug("JoinCode claim: key={}, acquired={}", key, acquired);
        return acquired;
    }
}
