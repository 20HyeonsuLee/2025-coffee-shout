package coffeeshout.global.scheduler;

/**
 * 소비된 지연 작업의 실행 주체. 타입당 하나의 핸들러 bean을 등록한다.
 */
public interface DelayedTaskHandler {

    DelayedTaskType supportedType();

    void handle(String taskId, String payload);
}
