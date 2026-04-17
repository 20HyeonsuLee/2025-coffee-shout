package coffeeshout.global.luacommand.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 도메인 메서드에 Lua 스크립트를 매핑한다.
 *
 * <p>하나의 메서드는 <b>검증 전용</b> 또는 <b>쓰기 전용</b> 중 하나로 역할을 명시한다.
 * {@link #validation()}이 {@code true}면 검증 스크립트, {@code false}면 쓰기 스크립트.
 * 검증과 쓰기를 한 메서드에 섞지 말고, 서비스 레이어에서 순서대로 조합해 호출한다.
 *
 * <p>메서드가 호출되면 AOP가 가로채서 {@link RedisTransactional} 스코프의 큐에 기록한다.
 * 트랜잭션 종료 시 {@code validation=true} 스크립트들이 Phase 1에 먼저 합성 실행되고,
 * 전부 통과하면 {@code validation=false} 스크립트들이 Phase 2에 이어 실행된다.
 *
 * <p>규약:
 * <ul>
 *   <li>{@code validation=true}: {@code redis.call}로 읽기만. 쓰기 명령(SADD/HSET/DEL/PUBLISH) 금지.
 *       실패 시 {@code return -N} (N은 {@link ReturnCode#value()}로 매핑)</li>
 *   <li>{@code validation=false}: 쓰기만. {@code return} 문 금지 (라이브러리가 자동으로 {@code return 1} 추가)</li>
 * </ul>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface LuaCommand {

    String script();

    boolean validation() default false;

    String[] keys() default {};

    String[] args() default {};

    ReturnCode[] returns() default {};
}
