package coffeeshout.test.config;

import java.util.concurrent.atomic.AtomicLong;
import org.springframework.core.task.TaskExecutor;

public class CountingTaskExecutor implements TaskExecutor {

    private final TaskExecutor delegate;
    private final AtomicLong activeCount = new AtomicLong();
    private final AtomicLong totalCount = new AtomicLong();

    public CountingTaskExecutor(TaskExecutor delegate) {
        this.delegate = delegate;
    }

    @Override
    public void execute(Runnable task) {
        totalCount.incrementAndGet();
        activeCount.incrementAndGet();
        delegate.execute(() -> {
            try {
                task.run();
            } finally {
                activeCount.decrementAndGet();
            }
        });
    }

    public long getActiveCount() {
        return activeCount.get();
    }

    public long getTotalCount() {
        return totalCount.get();
    }
}
