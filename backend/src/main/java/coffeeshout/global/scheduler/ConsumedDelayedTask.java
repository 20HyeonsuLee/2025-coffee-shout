package coffeeshout.global.scheduler;

/**
 * 폴링으로 소비된 작업. 소비 시점에 Redis에서 이미 제거된 상태다.
 *
 * @param taskKey           {@code {TYPE}:{id}} 형식의 작업 키
 * @param scheduledAtMillis 예정 실행 시각 (epoch millis, ZSET score)
 * @param payload           등록 시 저장한 payload. 등록 직후 취소와 경합하면 빈 문자열일 수 있다.
 */
public record ConsumedDelayedTask(String taskKey, long scheduledAtMillis, String payload) {

    public DelayedTaskType type() {
        return DelayedTaskType.valueOf(taskKey.substring(0, typeSeparatorIndex()));
    }

    public String taskId() {
        return taskKey.substring(typeSeparatorIndex() + 1);
    }

    private int typeSeparatorIndex() {
        final int index = taskKey.indexOf(':');
        if (index < 0) {
            throw new IllegalStateException("잘못된 taskKey 형식: " + taskKey);
        }
        return index;
    }
}
