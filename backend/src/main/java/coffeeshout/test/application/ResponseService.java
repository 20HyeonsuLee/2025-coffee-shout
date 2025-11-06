package coffeeshout.test.application;

import coffeeshout.global.websocket.LoggingSimpMessagingTemplate;
import coffeeshout.test.application.dto.OutboundResponse;
import coffeeshout.test.config.LoadConfig;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ResponseService {

    private final LoggingSimpMessagingTemplate messageTemplate;
    private final LoadSimulator loadSimulator;
    private final LoadConfig loadConfig;
    private final TaskScheduler scheduler;
    private final AtomicLong counter = new AtomicLong();

    private ScheduledFuture<?> future;
    private int currentTps = 10;

    public void start() {
        counter.set(0);
        long intervalMillis = 1000L / currentTps;
        future = scheduler.scheduleAtFixedRate(this::broadcastResponse, Duration.ofMillis(intervalMillis));
    }

    public void stop() {
        if (future == null) {
            return;
        }
        future.cancel(true);
    }

    public void setTps(int tps) {
        this.currentTps = tps;
    }

    public void broadcastResponse() {
        loadSimulator.simulate(loadConfig.getLoadType(), loadConfig.getLoadDurationMs());

        final var response = OutboundResponse.from(counter.incrementAndGet());
        messageTemplate.convertAndSend("/topic/test/load/response", response);
    }
}
