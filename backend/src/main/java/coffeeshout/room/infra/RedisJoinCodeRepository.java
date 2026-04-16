package coffeeshout.room.infra;

import coffeeshout.global.metric.LuaScriptMetricService;
import coffeeshout.room.domain.JoinCode;
import coffeeshout.room.domain.repository.JoinCodeRepository;
import java.time.Duration;
import java.util.List;
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
public class RedisJoinCodeRepository implements JoinCodeRepository {

    private static final String KEY_PREFIX = "joincode:";

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisScript<Long> claimJoinCodeScript;
    private final LuaScriptMetricService luaMetric;

    @Value("${room.removalDelay}")
    private Duration ttl;

    public RedisJoinCodeRepository(
            final StringRedisTemplate stringRedisTemplate,
            final RedisScript<Long> claimJoinCodeScript,
            final LuaScriptMetricService luaMetric
    ) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.claimJoinCodeScript = claimJoinCodeScript;
        this.luaMetric = luaMetric;
    }

    @Override
    public boolean save(final JoinCode joinCode) {
        final String key = KEY_PREFIX + joinCode.getValue();
        final long ttlSeconds = ttl.toSeconds();

        final long start = System.nanoTime();
        final Long result = stringRedisTemplate.execute(
                claimJoinCodeScript,
                List.of(key),
                String.valueOf(ttlSeconds)
        );
        final long durationNanos = System.nanoTime() - start;
        luaMetric.recordExecution("claim_joincode", result != null ? result : -99, durationNanos);

        final boolean acquired = Long.valueOf(1L).equals(result);
        log.debug("JoinCode claim: key={}, acquired={}", key, acquired);
        return acquired;
    }
}
