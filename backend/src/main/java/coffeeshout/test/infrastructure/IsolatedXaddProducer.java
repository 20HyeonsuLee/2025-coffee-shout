package coffeeshout.test.infrastructure;

import io.lettuce.core.RedisClient;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.codec.StringCodec;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class IsolatedXaddProducer {

    private final RedisClient redisClient;
    private final int connectionCount;
    private final String streamKey;
    private final int maxLength;
    private final Timer xaddTimer;

    private final List<StatefulRedisConnection<String, String>> connections = new ArrayList<>();
    private final List<RedisAsyncCommands<String, String>> asyncCommandsList = new ArrayList<>();
    private final AtomicInteger counter = new AtomicInteger(0);

    public IsolatedXaddProducer(
            @Qualifier("loadtestRedisClient") final RedisClient redisClient,
            final MeterRegistry meterRegistry,
            @Value("${loadtest.xadd.connection-count:4}") final int connectionCount,
            @Value("${loadtest.xadd.stream-key:loadtest:xadd}") final String streamKey,
            @Value("${loadtest.xadd.max-length:1000}") final int maxLength
    ) {
        this.redisClient = redisClient;
        this.connectionCount = connectionCount;
        this.streamKey = streamKey;
        this.maxLength = maxLength;
        this.xaddTimer = Timer.builder("loadtest.xadd.latency")
                .description("Isolated XADD round-trip latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    @PostConstruct
    public void init() {
        for (int i = 0; i < connectionCount; i++) {
            final StatefulRedisConnection<String, String> conn = redisClient.connect(StringCodec.UTF8);
            connections.add(conn);
            asyncCommandsList.add(conn.async());
        }
        log.info("IsolatedXaddProducer: 커넥션 {}개 초기화 완료", connections.size());
    }

    @PreDestroy
    public void destroy() {
        for (final StatefulRedisConnection<String, String> conn : connections) {
            conn.closeAsync();
        }
    }

    public void xaddAsync(final long sequenceNumber) {
        final String payload = "{\"seq\":" + sequenceNumber + ",\"ts\":" + System.currentTimeMillis() + "}";
        final XAddArgs xAddArgs = new XAddArgs()
                .maxlen(maxLength)
                .approximateTrimming();

        final int idx = Math.abs(counter.getAndIncrement() % asyncCommandsList.size());
        final long startNanos = System.nanoTime();

        asyncCommandsList.get(idx)
                .xadd(streamKey, xAddArgs, "payload", payload)
                .whenComplete((messageId, throwable) -> {
                    xaddTimer.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
                    if (throwable != null) {
                        log.error("loadtest XADD 실패: seq={}", sequenceNumber, throwable);
                    }
                });
    }
}
