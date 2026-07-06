package coffeeshout.global.config;

import coffeeshout.global.scheduler.DelayedTask;
import coffeeshout.global.scheduler.DelayedTaskScheduler;
import coffeeshout.global.scheduler.DelayedTaskType;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 테스트용 in-memory {@link DelayedTaskScheduler}.
 *
 * <p>"test" 프로파일은 Redis를 기동하지 않으므로 등록/취소만 기록하고 실행하지 않는다.
 */
public class FakeDelayedTaskScheduler implements DelayedTaskScheduler {

    private final Map<String, DelayedTask> scheduledTasks = new ConcurrentHashMap<>();

    @Override
    public void schedule(final DelayedTask task, final Instant executeAt) {
        scheduledTasks.put(task.taskKey(), task);
    }

    @Override
    public void cancel(final DelayedTaskType type, final String taskId) {
        scheduledTasks.remove(DelayedTask.taskKey(type, taskId));
    }

    public boolean isScheduled(final DelayedTaskType type, final String taskId) {
        return scheduledTasks.containsKey(DelayedTask.taskKey(type, taskId));
    }
}
