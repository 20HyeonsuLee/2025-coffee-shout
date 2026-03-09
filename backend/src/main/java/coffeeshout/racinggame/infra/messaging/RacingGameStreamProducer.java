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
import io.lettuce.core.resource.EventLoopGroupProvider;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.SingleThreadEventExecutor;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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

    private EventLoopGroup xaddEventLoopGroup;
    private ClientResources xaddClientResources;
    private RedisClient xaddClient;
    private ScheduledExecutorService metricsScheduler;

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
        if (asyncEnabled) {
            xaddEventLoopGroup = new NioEventLoopGroup(ioThreads);

            final MicrometerOptions options = MicrometerOptions.builder()
                    .enable()
                    .histogram(true)
                    .build();

            xaddClientResources = ClientResources.builder()
                    .eventLoopGroupProvider(new FixedEventLoopGroupProvider(xaddEventLoopGroup, ioThreads))
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

            registerEventLoopMetrics();

            log.info("RacingGameStreamProducer: async 모드 활성화 (격리된 EventLoopGroup io-threads={}, 커넥션 {}개)",
                    ioThreads, connections.size());
        } else {
            log.info("RacingGameStreamProducer: blocking 모드 활성화");
        }
    }

    @PreDestroy
    public void destroy() {
        if (metricsScheduler != null) {
            metricsScheduler.shutdown();
        }
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
        final String streamKey = redisStreamProperties.racingGameKey();
        final XAddArgs xAddArgs = new XAddArgs()
                .maxlen(redisStreamProperties.maxLength())
                .approximateTrimming();

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

    private void registerEventLoopMetrics() {
        int idx = 0;
        for (final EventExecutor executor : xaddEventLoopGroup) {
            if (executor instanceof SingleThreadEventExecutor singleExecutor) {
                final String threadIdx = String.valueOf(idx++);
                Gauge.builder("racing.xadd.eventloop.pending_tasks", singleExecutor,
                                SingleThreadEventExecutor::pendingTasks)
                        .tag("thread", threadIdx)
                        .register(meterRegistry);
            }
        }

        final Timer scheduleDelayTimer = Timer.builder("racing.xadd.eventloop.schedule_delay")
                .description("Event loop queue scheduling delay")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        metricsScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "xadd-eventloop-metrics");
            thread.setDaemon(true);
            return thread;
        });

        metricsScheduler.scheduleAtFixedRate(() -> {
            for (final EventExecutor executor : xaddEventLoopGroup) {
                final long submitNanos = System.nanoTime();
                executor.execute(() ->
                        scheduleDelayTimer.record(System.nanoTime() - submitNanos, TimeUnit.NANOSECONDS)
                );
            }
        }, 100, 100, TimeUnit.MILLISECONDS);
    }

    private static class FixedEventLoopGroupProvider implements EventLoopGroupProvider {

        private final EventLoopGroup eventLoopGroup;
        private final int threadPoolSize;

        FixedEventLoopGroupProvider(final EventLoopGroup eventLoopGroup, final int threadPoolSize) {
            this.eventLoopGroup = eventLoopGroup;
            this.threadPoolSize = threadPoolSize;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends EventLoopGroup> T allocate(final Class<T> type) {
            return (T) eventLoopGroup;
        }

        @Override
        public int threadPoolSize() {
            return threadPoolSize;
        }

        @Override
        public Future<Boolean> release(final EventExecutorGroup eventLoopGroup,
                                       final long quietPeriod, final long timeout, final TimeUnit unit) {
            return GlobalEventExecutor.INSTANCE.newSucceededFuture(true);
        }

        @Override
        public Future<Boolean> shutdown(final long quietPeriod, final long timeout, final TimeUnit unit) {
            eventLoopGroup.shutdownGracefully(quietPeriod, timeout, unit);
            return GlobalEventExecutor.INSTANCE.newSucceededFuture(true);
        }
    }
}
