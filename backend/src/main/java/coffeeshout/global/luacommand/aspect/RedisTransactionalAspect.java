package coffeeshout.global.luacommand.aspect;

import coffeeshout.global.luacommand.annotation.RedisTransactional;
import coffeeshout.global.luacommand.core.CommandContext;
import coffeeshout.global.luacommand.core.LuaCommandExecutor;
import coffeeshout.global.luacommand.core.QueuedCommand;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * {@link RedisTransactional}이 붙은 메서드의 진입/종료에서 {@link CommandContext}를 관리한다.
 *
 * <p>정상 종료 시 큐에 쌓인 {@link QueuedCommand}들을 {@link LuaCommandExecutor}로 일괄 실행한다.
 * 예외 시 큐를 비우고 예외를 전파한다.
 *
 * <p>{@link LuaCommandAspect}보다 먼저 실행돼야 하므로 {@code HIGHEST_PRECEDENCE + 10}으로 설정.
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
@Slf4j
public class RedisTransactionalAspect {

    private final LuaCommandExecutor executor;

    @Around("@annotation(coffeeshout.global.luacommand.annotation.RedisTransactional)")
    public Object intercept(final ProceedingJoinPoint pjp) throws Throwable {
        final boolean outermost = CommandContext.begin();
        try {
            final Object result = pjp.proceed();
            if (outermost) {
                final List<QueuedCommand> commands = CommandContext.drain();
                log.debug("RedisTransactional flushing {} command(s)", commands.size());
                executor.executeAll(commands);
            }
            return result;
        } catch (Throwable t) {
            if (outermost) {
                CommandContext.clear();
            }
            throw t;
        } finally {
            CommandContext.end();
        }
    }
}
