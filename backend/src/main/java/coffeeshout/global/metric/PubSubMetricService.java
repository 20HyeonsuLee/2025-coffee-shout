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

    public void recordPropagationDelay(final long publishedAtMillis, final long receivedAtMillis) {
        final long delayMs = receivedAtMillis - publishedAtMillis;
        Timer.builder("pubsub.propagation.delay")
                .register(meterRegistry)
                .record(delayMs, TimeUnit.MILLISECONDS);
    }
}
