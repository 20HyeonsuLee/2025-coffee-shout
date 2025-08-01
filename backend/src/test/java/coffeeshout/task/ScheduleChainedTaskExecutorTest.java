package coffeeshout.task;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class ScheduleChainedTaskExecutorTest {

    TaskScheduler scheduler = new ThreadPoolTaskScheduler() {{
        this.setPoolSize(1);
        this.setThreadNamePrefix("my-task-");
        this.initialize();
    }};

    @Test
    void testChainedTaskExecution() throws Exception {
        // given
        CompletableFutureChainedTask task1 = new CompletableFutureChainedTask(() -> System.out.println("Task 1 executed: "), Duration.ofMillis(1000));
        CompletableFutureChainedTask task2 = new CompletableFutureChainedTask(() -> System.out.println("Task 2 executed: "), Duration.ofMillis(1000));
        CompletableFutureChainedTask task3 = new CompletableFutureChainedTask(() -> System.out.println("Task 3 executed: "), Duration.ofMillis(1000));

        task1.setNextTask(task2);
        task2.setNextTask(task3);

        // when
        task1.start();

        // then - 첫 번째 작업 완료 대기
        task1.join();
        // 체인의 마지막 작업까지 완료되기를 기다림
    }

    @Test
    void TaskExecutorTest() throws InterruptedException, ExecutionException {
        // given
        ScheduleChainedTask scheduleChainedTask1 = new ScheduleChainedTask(() -> System.out.println("first"), Duration.ofMillis(0));
        ScheduleChainedTask scheduleChainedTask2 = new ScheduleChainedTask(() -> System.out.println("first wait done"),
                Duration.ofMillis(2000));
        ScheduleChainedTask scheduleChainedTask3 = new ScheduleChainedTask(() -> System.out.println("second"), Duration.ofMillis(0));
        ScheduleChainedTask scheduleChainedTask4 = new ScheduleChainedTask(() -> System.out.println("second wait done"),
                Duration.ofMillis(2000));

        scheduleChainedTask1.setNextTask(scheduleChainedTask2);
        scheduleChainedTask2.setNextTask(scheduleChainedTask3);
        scheduleChainedTask3.setNextTask(scheduleChainedTask4);

        scheduleChainedTask1.start(scheduler);

        scheduleChainedTask1.join();

        // when

        // then
    }
}
