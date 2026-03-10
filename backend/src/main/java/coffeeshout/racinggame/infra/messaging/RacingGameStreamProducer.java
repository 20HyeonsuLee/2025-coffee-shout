package coffeeshout.racinggame.infra.messaging;

import coffeeshout.global.config.properties.RedisProperties;
import coffeeshout.global.config.properties.RedisStreamProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.metrics.MicrometerCommandLatencyRecorder;
import io.lettuce.core.metrics.MicrometerOptions;
import io.lettuce.core.resource.ClientResources;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RacingGameStreamProducer {

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisStreamProperties redisStreamProperties;
    private final RedisProperties redisProperties;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final boolean asyncEnabled;
    private final int asyncConnectionCount;
    private final int ioThreads;
    private final Timer xaddTimer;

    private final List<StatefulRedisConnection<String, String>> connections = new ArrayList<>();
    private final List<RedisAsyncCommands<String, String>> asyncCommandsList = new ArrayList<>();
    private final AtomicInteger counter = new AtomicInteger(0);

    private ClientResources xaddClientResources;
    private RedisClient xaddClient;

    public RacingGameStreamProducer(
            final StringRedisTemplate stringRedisTemplate,
            final RedisStreamProperties redisStreamProperties,
            final RedisProperties redisProperties,
            final ObjectMapper objectMapper,
            final MeterRegistry meterRegistry,
            @Value("${racing-game.stream.async:false}") final boolean asyncEnabled,
            @Value("${racing-game.stream.async-connection-count:2}") final int asyncConnectionCount,
            @Value("${racing-game.stream.io-threads:2}") final int ioThreads
    ) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.redisStreamProperties = redisStreamProperties;
        this.redisProperties = redisProperties;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.asyncEnabled = asyncEnabled;
        this.asyncConnectionCount = asyncConnectionCount;
        this.ioThreads = ioThreads;
        this.xaddTimer = Timer.builder("racing.xadd.latency")
                .description("XADD async round-trip latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    @PostConstruct
    public void init() {
        log.info("OpenSSL available: {}", io.netty.handler.ssl.OpenSsl.isAvailable());
        if (asyncEnabled) {
            final MicrometerOptions options = MicrometerOptions.builder()
                    .enable()
                    .histogram(true)
                    .build();

            xaddClientResources = ClientResources.builder()
                    .ioThreadPoolSize(ioThreads)
                    .computationThreadPoolSize(ioThreads)
                    .commandLatencyRecorder(new MicrometerCommandLatencyRecorder(meterRegistry, options))
                    .build();

            final RedisURI.Builder uriBuilder = RedisURI.builder()
                    .withHost(redisProperties.host())
                    .withPort(redisProperties.port());

            if (redisProperties.ssl().enabled()) {
                uriBuilder.withSsl(true);
            }

            xaddClient = RedisClient.create(xaddClientResources, uriBuilder.build());

            for (int i = 0; i < asyncConnectionCount; i++) {
                final StatefulRedisConnection<String, String> conn = xaddClient.connect(StringCodec.UTF8);
                connections.add(conn);
                asyncCommandsList.add(conn.async());
            }

            log.info("RacingGameStreamProducer: async 모드 활성화 (Lettuce 기본 EventLoop io-threads={}, 커넥션 {}개)",
                    ioThreads, connections.size());
        } else {
            log.info("RacingGameStreamProducer: blocking 모드 활성화");
        }
    }

    @PreDestroy
    public void destroy() {
        for (final StatefulRedisConnection<String, String> conn : connections) {
            conn.closeAsync();
        }
        if (xaddClient != null) {
            xaddClient.shutdown();
        }
        if (xaddClientResources != null) {
            xaddClientResources.shutdown();
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
        final String payload = "{\"seq\":" + ",\"ts\":" + System.currentTimeMillis() + "}";
        final String streamKey = redisStreamProperties.racingGameKey();
        final XAddArgs xAddArgs = new XAddArgs()
                .maxlen(redisStreamProperties.maxLength())
                .approximateTrimming();

        final int idx = Math.abs(counter.getAndIncrement() % asyncCommandsList.size());
        final long startNanos = System.nanoTime();

        asyncCommandsList.get(idx)
                .xadd("loadtest:xadd", xAddArgs, "payload", payload)
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
