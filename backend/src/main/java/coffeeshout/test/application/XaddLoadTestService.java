package coffeeshout.test.application;

import coffeeshout.test.application.dto.XaddLoadTestRequest;
import coffeeshout.test.application.dto.XaddLoadTestResponse;
import coffeeshout.test.infrastructure.IsolatedXaddProducer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class XaddLoadTestService {

    private final IsolatedXaddProducer isolatedXaddProducer;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong sequenceCounter = new AtomicLong(0);
    private volatile ScheduledExecutorService scheduler;
    private volatile ScheduledFuture<?> loadTask;
    private volatile ScheduledFuture<?> stopTask;

    public XaddLoadTestResponse startLoadTest(final XaddLoadTestRequest request) {
        if (!running.compareAndSet(false, true)) {
            return new XaddLoadTestResponse(request.tps(), request.durationSeconds(), "ALREADY_RUNNING");
        }

        sequenceCounter.set(0);
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "xadd-loadtest-scheduler");
            thread.setDaemon(true);
            return thread;
        });

        final int tps = request.tps();
        final int intervalMs = tps <= 1000 ? 1000 / tps : 1;
        final int batchPerTick = tps <= 1000 ? 1 : tps / 1000;

        log.info("XADD 부하테스트 시작: tps={}, duration={}s, intervalMs={}, batchPerTick={}",
                tps, request.durationSeconds(), intervalMs, batchPerTick);

        loadTask = scheduler.scheduleAtFixedRate(
                () -> fireBatch(batchPerTick),
                0, intervalMs, TimeUnit.MILLISECONDS
        );

        stopTask = scheduler.schedule(
                this::stopLoadTest,
                request.durationSeconds(), TimeUnit.SECONDS
        );

        return new XaddLoadTestResponse(request.tps(), request.durationSeconds(), "STARTED");
    }

    public void stopLoadTest() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        if (loadTask != null) {
            loadTask.cancel(false);
        }
        if (stopTask != null) {
            stopTask.cancel(false);
        }
        if (scheduler != null) {
            scheduler.shutdown();
        }

        log.info("XADD 부하테스트 종료: 총 {}건 발송", sequenceCounter.get());
    }

    public boolean isRunning() {
        return running.get();
    }

    private void fireBatch(final int batchSize) {
        for (int i = 0; i < batchSize; i++) {
            isolatedXaddProducer.xaddAsync(sequenceCounter.getAndIncrement());
        }
    }
}
