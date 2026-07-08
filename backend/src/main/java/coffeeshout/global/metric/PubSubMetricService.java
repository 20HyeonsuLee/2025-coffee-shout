package coffeeshout.global.metric;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class PubSubMetricService {

    private final MeterRegistry meterRegistry;

    public PubSubMetricService(final MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordPublish(final String eventType) {
        Counter.builder("pubsub.message.published.total")
                .tag("eventType", eventType)
                .register(meterRegistry)
                .increment();
    }

    public void recordReceive(final String eventType) {
        Counter.builder("pubsub.message.received.total")
                .tag("eventType", eventType)
                .register(meterRegistry)
                .increment();
    }

    public void recordSelfSkip() {
        Counter.builder("pubsub.message.self.skip.total")
                .register(meterRegistry)
                .increment();
    }

    public void recordStaleDrop(final String eventType) {
        Counter.builder("pubsub.message.stale.drop.total")
                .tag("eventType", eventType)
                .register(meterRegistry)
                .increment();
    }

    public void recordGapDetected(final String eventType) {
        Counter.builder("pubsub.message.gap.detected.total")
                .tag("eventType", eventType)
                .register(meterRegistry)
                .increment();
    }

    public void recordSnapshotResync(final String eventType, final long durationNanos) {
        Counter.builder("room.snapshot.resync.total")
                .tag("eventType", eventType)
                .register(meterRegistry)
                .increment();
        Timer.builder("room.snapshot.resync.duration")
                .tag("eventType", eventType)
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    public void recordSnapshotRead(final String eventType) {
        Counter.builder("room.snapshot.read.total")
                .tag("eventType", eventType)
                .register(meterRegistry)
                .increment();
    }

    public void recordSnapshotResyncCoalesced(final String eventType) {
        Counter.builder("room.snapshot.resync.coalesced.total")
                .tag("eventType", eventType)
                .register(meterRegistry)
                .increment();
    }

    public void recordSnapshotResyncCooldownSkip(final String eventType) {
        Counter.builder("room.snapshot.resync.cooldown.skip.total")
                .tag("eventType", eventType)
                .register(meterRegistry)
                .increment();
    }

    public void recordSnapshotResyncRetry(final String eventType) {
        Counter.builder("room.snapshot.resync.retry.total")
                .tag("eventType", eventType)
                .register(meterRegistry)
                .increment();
    }

    public void recordSnapshotResyncFailed(final String eventType) {
        Counter.builder("room.snapshot.resync.failed.total")
                .tag("eventType", eventType)
                .register(meterRegistry)
                .increment();
    }

    public void recordPropagationDelay(final long publishedAtMillis, final long receivedAtMillis) {
        final long delayMs = receivedAtMillis - publishedAtMillis;
        Timer.builder("pubsub.propagation.delay")
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(delayMs, TimeUnit.MILLISECONDS);
    }
}
