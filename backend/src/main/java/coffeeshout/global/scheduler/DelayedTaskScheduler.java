package coffeeshout.global.scheduler;

import java.time.Instant;

/**
 * 분산 지연 작업 스케줄러.
 *
 * <p>WAS 메모리 타이머({@code ScheduledExecutorService})와 달리 스케줄이 WAS 밖에 보존되어야 한다.
 * 어느 인스턴스가 등록했든 다른 인스턴스가 취소/실행할 수 있어야 한다.
 */
public interface DelayedTaskScheduler {

    void schedule(DelayedTask task, Instant executeAt);

    void cancel(DelayedTaskType type, String taskId);
}
