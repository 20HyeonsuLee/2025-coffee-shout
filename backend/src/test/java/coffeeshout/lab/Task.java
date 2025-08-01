package coffeeshout.lab;

import java.util.Date;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;

class Task {
    private Long id;
    private final Runnable runnable;
    private final Long duration;
    private Task nextTask = null;

    public Task(Long id, Runnable runnable, Long duration) {
        this.id = id;
        this.runnable = runnable;
        this.duration = duration;
    }

    public Task append(Task nextTask) {
        this.nextTask = nextTask;
        return nextTask;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof final Task task)) {
            return false;
        }
        return Objects.equals(id, task.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    public Long getId() {
        return this.id;
    }

    public Task getNextTask() {
        if (nextTask == null) {
            throw new IllegalStateException("다음 작업이 없습니다.");
        }
        return nextTask;
    }

    public boolean hasNextTask() {
        return nextTask != null;
    }

    public ScheduledFuture<?> start(TaskExecutor executor, Map<Long, ScheduledFuture<?>> futures) {
        Runnable taskRunnable = () -> {
            runnable.run();
            if (hasNextTask()) {
                ScheduledFuture<?> schedule = getNextTask().start(executor, futures);
                executor.setCurrentTask(getNextTask());
                futures.put(getNextTask().getId(), schedule);
            }
        };
        return executor.scheduler.schedule(
                taskRunnable,
                new Date(System.currentTimeMillis() + duration).toInstant()
        );
    }
}
