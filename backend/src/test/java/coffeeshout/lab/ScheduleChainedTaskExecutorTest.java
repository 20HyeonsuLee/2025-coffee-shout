package coffeeshout.lab;

import org.junit.jupiter.api.Test;

class ScheduleChainedTaskExecutorTest {

    @Test
    void TaskExecutorTest() throws InterruptedException {
        // given
        Task task = new Task(0L, () -> System.out.println("first"), 0L);

        Task task2 = new Task(1L, () -> System.out.println("first wait done"), 2000L);
        Task task3 = task.append(task2)
                .append(new Task(2L, () -> System.out.println("second"), 0L));
        Task task4 = new Task(3L, () -> System.out.println("second wait done"), 2000L);
        task3
                .append(task4);

        TaskExecutor taskExecutor = new TaskExecutor(task);

        taskExecutor.start();

        Thread.sleep(1000);
        taskExecutor.cancelCurrentFuture(task2);
        taskExecutor.start();

        Thread.sleep(5000);


        // when

        // then
    }}
