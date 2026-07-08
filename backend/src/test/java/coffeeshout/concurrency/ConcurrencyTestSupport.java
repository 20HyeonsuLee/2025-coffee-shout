package coffeeshout.concurrency;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

/**
 * Redis 기반 동시성 테스트의 공통 베이스.
 *
 * <p>Testcontainers singleton 패턴: static initializer에서 한 번만 start,
 * JVM shutdown hook이 정리. 여러 테스트 클래스가 공유한다.
 * {@code @Testcontainers}/{@code @Container}는 사용하지 않는다 (클래스별 stop 방지).
 *
 * <p>프로파일 "concurrency"로 @Profile("!test") 활성화.
 * @BeforeEach에서 Redis 전체 초기화 (테스트 격리).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("concurrency")
public abstract class ConcurrencyTestSupport {

    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2-alpine")
            .withExposedPorts(6379);

    static {
        REDIS.start();
    }

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
