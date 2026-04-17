package coffeeshout.global.luacommand.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 메서드 실행 범위를 하나의 Redis 트랜잭션 경계로 지정한다.
 *
 * <p>메서드 진입 시 {@link coffeeshout.global.luacommand.core.CommandContext}가 새 큐를 연다.
 * 내부에서 호출된 {@link LuaCommand} 메서드들은 즉시 실행되지 않고 큐에 쌓인다.
 * 메서드 정상 종료 시 큐 전체가 하나의 composite Lua 스크립트로 합성되어 실행된다.
 *
 * <p>예외 발생 시 큐를 비우고 예외를 전파한다. Redis에 반영된 부분 상태는 롤백하지 않는다
 * (Redis 구조적 한계).
 *
 * <p>중첩 호출은 outermost의 트랜잭션 경계만 유효하며, 내부 {@code @RedisTransactional}은 무시된다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RedisTransactional {
}
