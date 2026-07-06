package coffeeshout.global.scheduler;

/**
 * 분산 지연 작업의 종류.
 *
 * <p>taskKey는 {@code {TYPE}:{id}} 형식으로 Redis에 저장되므로
 * enum 이름에 콜론을 쓰면 안 된다.
 */
public enum DelayedTaskType {
    PLAYER_REMOVAL
}
