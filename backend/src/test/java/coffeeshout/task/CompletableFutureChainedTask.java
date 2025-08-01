package coffeeshout.task;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class CompletableFutureChainedTask {
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(10);

    private final Runnable runnable;
    private final Duration delay;
    private CompletableFutureChainedTask nextChainedTask;
    private CompletableFuture<Void> future;

    public CompletableFutureChainedTask(Runnable runnable, Duration delay) {
        this.runnable = runnable;
        this.delay = delay;
    }

    public void start() {
        if (isStarted()) {
            throw new IllegalStateException("Task already started");
        }

        future = CompletableFuture
                .runAsync(chainedRunnable(),
                        CompletableFuture.delayedExecutor(delay.toMillis(), TimeUnit.MILLISECONDS, scheduler))
                .exceptionally(throwable -> {
                    System.err.println("Task execution failed: " + throwable.getMessage());
                    return null;
                });
    }

    public void setNextTask(CompletableFutureChainedTask nextChainedTask) {
        this.nextChainedTask = nextChainedTask;
    }

    public void cancel() {
        if (!isStarted()) {
            return;
        }
        future.cancel(false);
    }

    public CompletableFuture<Void> getFuture() {
        return future;
    }

    public boolean isCompleted() {
        return future != null && future.isDone();
    }

    public boolean isCancelled() {
        return future != null && future.isCancelled();
    }

    private Runnable chainedRunnable() {
        if (nextChainedTask == null) {
            return runnable;
        }
        return () -> {
            runnable.run();
            nextChainedTask.start();
        };
    }

    private boolean isStarted() {
        return future != null;
    }

    // 리소스 정리를 위한 메서드
    public static void shutdown() {
        scheduler.shutdown();
    }

    public void join() throws ExecutionException, InterruptedException {
        this.future.get();
        if (nextChainedTask != null) {
            this.nextChainedTask.join();
        }
    }
}
