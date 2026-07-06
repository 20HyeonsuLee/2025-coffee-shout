package coffeeshout.global.metric;

import coffeeshout.global.scheduler.DelayedTaskType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class SchedulerMetricService {

    private final MeterRegistry meterRegistry;

    public SchedulerMetricService(final MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordScheduled(final DelayedTaskType type) {
        Counter.builder("scheduler.task.scheduled.total")
                .tag("type", type.name())
                .register(meterRegistry)
                .increment();
    }

    public void recordCancelled(final DelayedTaskType type) {
        Counter.builder("scheduler.task.cancelled.total")
                .tag("type", type.name())
                .register(meterRegistry)
                .increment();
    }

    /**
     * 소비 성공 + 예정 시각 대비 실제 실행까지의 지연(폴링 주기에 의한 오차) 기록.
     */
    public void recordConsumed(final DelayedTaskType type, final long fireDelayMillis) {
        Counter.builder("scheduler.task.consumed.total")
                .tag("type", type.name())
                .register(meterRegistry)
                .increment();
        Timer.builder("scheduler.task.fire.delay")
                .tag("type", type.name())
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(Math.max(0, fireDelayMillis), TimeUnit.MILLISECONDS);
    }

    public void recordFailed(final DelayedTaskType type) {
        Counter.builder("scheduler.task.failed.total")
                .tag("type", type.name())
                .register(meterRegistry)
                .increment();
    }
}
