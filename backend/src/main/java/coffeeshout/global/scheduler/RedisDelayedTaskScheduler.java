package coffeeshout.global.scheduler;

import coffeeshout.global.metric.SchedulerMetricService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis Sorted Set 기반 {@link DelayedTaskScheduler} 구현체.
 *
 * <p>구조:
 * <ul>
 *   <li>ZSET {@code {prefix}:tasks} — member=taskKey, score=실행 시각(epoch millis)</li>
 *   <li>STRING {@code {prefix}:payload:{taskKey}} — 실행 시점에 핸들러로 전달할 payload</li>
 * </ul>
 *
 * <p>소비는 Lua 스크립트로 조회(ZRANGEBYSCORE)와 삭제(ZREM)를 하나의 원자적 연산으로 묶는다.
 * 여러 WAS의 폴러가 경쟁해도 같은 작업을 두 번 가져갈 수 없으므로 별도의 분산 락이 필요 없다.
 *
 * <p>보장 범위: 전달은 at-least-once(스케줄이 Redis에 보존), 획득은 exactly-once(원자적 소비).
 * 단, 소비 직후 핸들러 실행 전에 프로세스가 죽으면 해당 작업은 유실된다 — Sidekiq 기본 fetch와 같은 트레이드오프.
 */
@Slf4j
@Component
@Profile("!test")
public class RedisDelayedTaskScheduler implements DelayedTaskScheduler {

    /**
     * KEYS[1]=ZSET, ARGV[1]=현재 시각(epoch millis), ARGV[2]=최대 개수, ARGV[3]=payload 키 prefix.
     * 반환: [taskKey, score, payload, ...] 3개 단위 평탄화 목록.
     */
    private static final String CONSUME_SCRIPT = """
            local due = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'WITHSCORES', 'LIMIT', 0, tonumber(ARGV[2]))
            local result = {}
            for i = 1, #due, 2 do
              local taskKey = due[i]
              redis.call('ZREM', KEYS[1], taskKey)
              local payloadKey = ARGV[3] .. taskKey
              local payload = redis.call('GET', payloadKey)
              redis.call('DEL', payloadKey)
              result[#result + 1] = taskKey
              result[#result + 1] = due[i + 1]
              result[#result + 1] = payload or ''
            end
            return result
            """;

    private static final Duration PAYLOAD_TTL_MARGIN = Duration.ofMinutes(10);

    private final StringRedisTemplate redisTemplate;
    private final SchedulerMetricService schedulerMetric;
    private final DefaultRedisScript<List> consumeScript;
    private final String tasksKey;
    private final String payloadKeyPrefix;

    public RedisDelayedTaskScheduler(
            final StringRedisTemplate redisTemplate,
            final SchedulerMetricService schedulerMetric,
            @Value("${scheduler.key-prefix:scheduler}") final String keyPrefix
    ) {
        this.redisTemplate = redisTemplate;
        this.schedulerMetric = schedulerMetric;
        this.consumeScript = new DefaultRedisScript<>(CONSUME_SCRIPT, List.class);
        this.tasksKey = keyPrefix + ":tasks";
        this.payloadKeyPrefix = keyPrefix + ":payload:";
    }

    @Override
    public void schedule(final DelayedTask task, final Instant executeAt) {
        final String taskKey = task.taskKey();
        final Duration payloadTtl = payloadTtl(executeAt);

        redisTemplate.opsForValue().set(payloadKeyPrefix + taskKey, task.payload(), payloadTtl);
        redisTemplate.opsForZSet().add(tasksKey, taskKey, executeAt.toEpochMilli());

        schedulerMetric.recordScheduled(task.type());
        log.info("지연 작업 등록: taskKey={}, executeAt={}", taskKey, executeAt);
    }

    @Override
    public void cancel(final DelayedTaskType type, final String taskId) {
        final String taskKey = DelayedTask.taskKey(type, taskId);
        final Long removed = redisTemplate.opsForZSet().remove(tasksKey, taskKey);
        redisTemplate.delete(payloadKeyPrefix + taskKey);

        if (removed != null && removed > 0) {
            schedulerMetric.recordCancelled(type);
            log.info("지연 작업 취소: taskKey={}", taskKey);
        }
    }

    /**
     * 실행 시각이 지난 작업을 원자적으로 가져오고 동시에 삭제한다.
     * 어느 인스턴스가 등록한 작업이든 가져갈 수 있다.
     */
    public List<ConsumedDelayedTask> consumeDue(final Instant now, final int limit) {
        final List<?> raw = redisTemplate.execute(
                consumeScript,
                Collections.singletonList(tasksKey),
                String.valueOf(now.toEpochMilli()),
                String.valueOf(limit),
                payloadKeyPrefix
        );

        if (raw == null || raw.isEmpty()) {
            return List.of();
        }

        final List<ConsumedDelayedTask> consumed = new ArrayList<>(raw.size() / 3);
        for (int i = 0; i + 2 < raw.size(); i += 3) {
            final String taskKey = String.valueOf(raw.get(i));
            final long scheduledAt = (long) Double.parseDouble(String.valueOf(raw.get(i + 1)));
            final String payload = String.valueOf(raw.get(i + 2));
            consumed.add(new ConsumedDelayedTask(taskKey, scheduledAt, payload));
        }
        return consumed;
    }

    private Duration payloadTtl(final Instant executeAt) {
        final Duration untilExecution = Duration.between(Instant.now(), executeAt);
        if (untilExecution.isNegative()) {
            return PAYLOAD_TTL_MARGIN;
        }
        return untilExecution.plus(PAYLOAD_TTL_MARGIN);
    }
}
