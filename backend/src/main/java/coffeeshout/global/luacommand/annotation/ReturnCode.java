package coffeeshout.global.luacommand.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Lua 스크립트의 특정 반환 코드를 예외 또는 성공으로 매핑한다.
 *
 * <p>{@code value == 1}은 기본 성공 코드이며 예외를 던지지 않는다.
 * {@code value <= 0}은 실패 코드이며 {@link #throwsException()}에 지정된 예외를 던진다.
 */
@Target(ElementType.ANNOTATION_TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ReturnCode {

    int value();

    Class<? extends RuntimeException> throwsException() default NoException.class;

    String message() default "";
}
