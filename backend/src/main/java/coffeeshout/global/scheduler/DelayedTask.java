package coffeeshout.global.scheduler;

/**
 * 스케줄 등록 요청 단위.
 *
 * @param type    작업 종류
 * @param id      작업 식별자 (예: playerKey). 같은 (type, id)로 다시 등록하면 실행 시각과 payload가 덮어써진다.
 * @param payload 실행 시점에 핸들러로 전달할 직렬화된 데이터
 */
public record DelayedTask(DelayedTaskType type, String id, String payload) {

    public String taskKey() {
        return taskKey(type, id);
    }

    public static String taskKey(final DelayedTaskType type, final String id) {
        return type.name() + ":" + id;
    }
}
