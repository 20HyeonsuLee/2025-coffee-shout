package coffeeshout.global.scheduler;

import coffeeshout.global.metric.SchedulerMetricService;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 주기적으로 Redis에서 실행 시각이 지난 작업을 가져와 핸들러로 분배한다.
 *
 * <p>push(Keyspace Notification)가 아닌 pull(폴링)을 쓰는 이유:
 * 알림은 놓치면 복구할 수 없지만, 데이터는 누군가 가져갈 때까지 Redis에 남는다.
 * WAS가 전부 재시작해도 다음 폴링 주기에 밀린 작업을 회수한다.
 */
@Slf4j
@Component
@Profile("!test")
public class DelayedTaskPoller {

    private final RedisDelayedTaskScheduler taskScheduler;
    private final SchedulerMetricService schedulerMetric;
    private final Map<DelayedTaskType, DelayedTaskHandler> handlers;
    private final int batchSize;

    public DelayedTaskPoller(
            final RedisDelayedTaskScheduler taskScheduler,
            final SchedulerMetricService schedulerMetric,
            final List<DelayedTaskHandler> handlerBeans,
            @Value("${scheduler.poll-batch-size:100}") final int batchSize
    ) {
        this.taskScheduler = taskScheduler;
        this.schedulerMetric = schedulerMetric;
        this.handlers = new EnumMap<>(DelayedTaskType.class);
        handlerBeans.forEach(handler -> handlers.put(handler.supportedType(), handler));
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${scheduler.poll-interval-ms:1000}")
    public void poll() {
        pollOnce(Instant.now());
    }

    public void pollOnce(final Instant now) {
        final List<ConsumedDelayedTask> tasks = taskScheduler.consumeDue(now, batchSize);
        for (final ConsumedDelayedTask task : tasks) {
            dispatch(task, now);
        }
    }

    private void dispatch(final ConsumedDelayedTask task, final Instant now) {
        final DelayedTaskType type;
        try {
            type = task.type();
        } catch (final IllegalArgumentException | IllegalStateException e) {
            log.error("알 수 없는 지연 작업 타입, 폐기: taskKey={}", task.taskKey(), e);
            return;
        }

        final DelayedTaskHandler handler = handlers.get(type);
        if (handler == null) {
            log.error("핸들러 미등록 지연 작업, 폐기: taskKey={}", task.taskKey());
            return;
        }

        try {
            handler.handle(task.taskId(), task.payload());
            schedulerMetric.recordConsumed(type, now.toEpochMilli() - task.scheduledAtMillis());
        } catch (final Exception e) {
            schedulerMetric.recordFailed(type);
            log.error("지연 작업 실행 실패: taskKey={}", task.taskKey(), e);
        }
    }
}
