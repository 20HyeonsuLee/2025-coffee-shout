package coffeeshout.room.infra;

import coffeeshout.room.domain.JoinCode;
import coffeeshout.room.domain.repository.JoinCodeRepository;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

/**
 * Redis SETNX 기반 JoinCode 유일성 보장 구현체.
 * claim_joincode.lua를 통해 SET NX EX 원자 실행.
 */
@Slf4j
@Repository
@Primary
@Profile("!test")
@RequiredArgsConstructor
public class RedisJoinCodeRepository implements JoinCodeRepository {

    private static final String KEY_PREFIX = "joincode:";

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisScript<Long> claimJoinCodeScript;

    @Value("${room.removalDelay}")
    private Duration ttl;

    @Override
    public boolean save(final JoinCode joinCode) {
        final String key = KEY_PREFIX + joinCode.getValue();
        final long ttlSeconds = ttl.toSeconds();

        final Long result = stringRedisTemplate.execute(
                claimJoinCodeScript,
                List.of(key),
                String.valueOf(ttlSeconds)
        );

        final boolean acquired = Long.valueOf(1L).equals(result);
        log.debug("JoinCode claim: key={}, acquired={}", key, acquired);
        return acquired;
    }
}
