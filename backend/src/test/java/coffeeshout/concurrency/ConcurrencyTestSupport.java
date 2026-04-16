package coffeeshout.concurrency;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Redis 기반 동시성 테스트의 공통 베이스.
 * - Testcontainers로 실제 Redis 7.2 컨테이너 기동
 * - @Profile("!test") 활성화 위해 프로파일은 "concurrency" 사용
 * - @BeforeEach에서 Redis 전체 초기화 (테스트 격리)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("concurrency")
@Testcontainers
public abstract class ConcurrencyTestSupport {

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2-alpine")
            .withExposedPorts(6379)
            .withReuse(false);

    @DynamicPropertySource
    static void registerRedisProperties(final DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379).toString());
    }

    @Autowired
    protected StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void flushRedis() {
        stringRedisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }
}
