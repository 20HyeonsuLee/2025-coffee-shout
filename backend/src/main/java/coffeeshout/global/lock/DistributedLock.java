package coffeeshout.global.lock;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 메서드 실행 동안 Redisson 분산 락을 건다.
 *
 * <p>{@link #key()}에 SpEL 템플릿을 쓸 수 있다.
 * {@code "room:#{#joinCode}"} 형태로 메서드 파라미터를 참조하면
 * 호출 시점의 실제 값으로 치환되어 락 키가 된다.
 *
 * <p>{@link #leaseSeconds()}가 양수면 고정 lease time을 사용하고,
 * 음수면 Redisson watchdog으로 락 점유를 연장한다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DistributedLock {

    String key();

    long waitSeconds() default 3;

    long leaseSeconds() default -1;
}
