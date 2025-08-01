package coffeeshout.task;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import org.springframework.scheduling.TaskScheduler;

public class ScheduleChainedTask {
    private final Runnable runnable;
    private final Duration delay;
    private ScheduledFuture<?> future;
    private ScheduleChainedTask nextScheduleChainedTask;

    public ScheduleChainedTask(Runnable runnable, Duration delay) {
        this.runnable = runnable;
        this.delay = delay;
    }

    public void start(TaskScheduler scheduler) {
        future = scheduler.schedule(chainedRunnable(scheduler), Instant.now().plus(delay));
    }

    public void setNextTask(ScheduleChainedTask nextScheduleChainedTask) {
        this.nextScheduleChainedTask = nextScheduleChainedTask;
    }

    public void cancel() {
        if (!isStarted()) {
            return;
        }
        future.cancel(false);
    }

    public void join() throws ExecutionException, InterruptedException {
        this.future.get();
        if (nextScheduleChainedTask != null) {
            this.nextScheduleChainedTask.join();
        }
    }

    private Runnable chainedRunnable(TaskScheduler scheduler) {
        if (nextScheduleChainedTask == null) {
            return runnable;
        }
        return () -> {
            runnable.run();
            nextScheduleChainedTask.start(scheduler);
        };
    }

    private boolean isStarted() {
        return future != null;
    }
}
