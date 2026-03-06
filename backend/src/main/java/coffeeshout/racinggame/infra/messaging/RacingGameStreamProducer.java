package coffeeshout.racinggame.infra.messaging;

import coffeeshout.global.config.properties.RedisStreamProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.codec.StringCodec;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class RacingGameStreamProducer {

    private final LettuceConnectionFactory connectionFactory;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedisStreamProperties redisStreamProperties;
    private final ObjectMapper objectMapper;
    private final boolean asyncEnabled;
    private final int asyncConnectionCount;
    private final Timer xaddTimer;

    // 단일 커넥션 → 여러 커넥션으로 분리
    private final List<StatefulRedisConnection<String, String>> connections = new ArrayList<>();
    private final List<RedisAsyncCommands<String, String>> asyncCommandsList = new ArrayList<>();
    private final AtomicInteger counter = new AtomicInteger(0);

    public RacingGameStreamProducer(
            final LettuceConnectionFactory connectionFactory,
            final StringRedisTemplate stringRedisTemplate,
            final RedisStreamProperties redisStreamProperties,
            final ObjectMapper objectMapper,
            final MeterRegistry meterRegistry,
            @Value("${racing-game.stream.async:false}") final boolean asyncEnabled,
            @Value("${racing-game.stream.async-connection-count:4}") final int asyncConnectionCount
    ) {
        this.connectionFactory = connectionFactory;
        this.stringRedisTemplate = stringRedisTemplate;
        this.redisStreamProperties = redisStreamProperties;
        this.objectMapper = objectMapper;
        this.asyncEnabled = asyncEnabled;
        this.asyncConnectionCount = asyncConnectionCount;
        this.xaddTimer = Timer.builder("racing.xadd.latency")
                .description("XADD async round-trip latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    @PostConstruct
    public void init() {
        if (asyncEnabled) {
            final RedisClient nativeClient = (RedisClient) connectionFactory.getNativeClient();
            for (int i = 0; i < asyncConnectionCount; i++) {
                final StatefulRedisConnection<String, String> conn = nativeClient.connect(StringCodec.UTF8);
                connections.add(conn);
                asyncCommandsList.add(conn.async());
            }
            log.info("RacingGameStreamProducer: async 모드 활성화 (커넥션 {}개)", connections.size());
        } else {
            log.info("RacingGameStreamProducer: blocking 모드 활성화");
        }
    }

    @PreDestroy
    public void destroy() {
        for (final StatefulRedisConnection<String, String> conn : connections) {
            conn.closeAsync();
        }
    }

    public <T> void publishEvent(final T event) {
        if (asyncEnabled) {
            publishAsync(event);
        } else {
            publishBlocking(event);
        }
    }

    private <T> void publishAsync(final T event) {
        final String eventJson = serialize(event);
        final String streamKey = redisStreamProperties.racingGameKey();
        final XAddArgs xAddArgs = new XAddArgs()
                .maxlen(redisStreamProperties.maxLength())
                .approximateTrimming();

        // 라운드로빈으로 커넥션 분산
        // Math.abs: Integer.MIN_VALUE 오버플로 방지
        final int idx = Math.abs(counter.getAndIncrement() % asyncCommandsList.size());
        final long startNanos = System.nanoTime();
        asyncCommandsList.get(idx)
                .xadd(streamKey, xAddArgs, "payload", eventJson)
                .whenComplete((messageId, throwable) -> {
                    xaddTimer.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
                    if (throwable != null) {
                        log.error("레이싱 게임 이벤트 발송 실패: streamKey={}", streamKey, throwable);
                    }
                });
    }

    private <T> void publishBlocking(final T event) {
        final String eventJson = serialize(event);
        final String streamKey = redisStreamProperties.racingGameKey();

        stringRedisTemplate.opsForStream().add(
                StreamRecords.newRecord().in(streamKey).ofMap(java.util.Map.of("payload", eventJson)),
                RedisStreamCommands.XAddOptions.maxlen(redisStreamProperties.maxLength()).approximateTrimming(true)
        );
    }

    private <T> String serialize(final T event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (final JsonProcessingException e) {
            throw new IllegalArgumentException("레이싱 게임 이벤트 직렬화 실패", e);
        }
    }
}
