package coffeeshout.global.metric;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class LuaScriptMetricService {

    private final MeterRegistry meterRegistry;

    public LuaScriptMetricService(final MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordExecution(final String scriptName, final long resultCode, final long durationNanos) {
        Counter.builder("lua.script.result.total")
                .tag("script", scriptName)
                .tag("result", String.valueOf(resultCode))
                .register(meterRegistry)
                .increment();

        Timer.builder("lua.script.execution.time")
                .tag("script", scriptName)
                .register(meterRegistry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }
}
