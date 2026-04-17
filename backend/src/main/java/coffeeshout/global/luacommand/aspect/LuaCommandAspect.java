package coffeeshout.global.luacommand.aspect;

import coffeeshout.global.luacommand.annotation.LuaCommand;
import coffeeshout.global.luacommand.core.CommandContext;
import coffeeshout.global.luacommand.core.LuaCommandExecutor;
import coffeeshout.global.luacommand.core.QueuedCommand;
import coffeeshout.global.luacommand.core.SpelResolver;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * {@link LuaCommand}가 붙은 메서드를 가로채서 {@link CommandContext} 큐에 기록한다.
 *
 * <p>활성 {@link coffeeshout.global.luacommand.annotation.RedisTransactional} 스코프가 있으면 큐잉만 하고,
 * 없으면 단일 명령으로 즉시 실행한다.
 *
 * <p>keys/args SpEL 표현식이 {@link Collection} 혹은 배열로 평가되면 각 원소를 개별 arg로 flatten.
 * 이는 {@code update_positions} 처럼 가변 길이 인자를 받는 명령을 위한 확장이다.
 */
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
@Slf4j
public class LuaCommandAspect {

    private final LuaCommandExecutor executor;

    @Around("@annotation(luaCommand)")
    public Object intercept(final ProceedingJoinPoint pjp, final LuaCommand luaCommand) throws Throwable {
        final Object result = pjp.proceed();

        final MethodSignature signature = (MethodSignature) pjp.getSignature();
        final Method method = signature.getMethod();
        final Object[] args = pjp.getArgs();

        final QueuedCommand queuedCommand = new QueuedCommand(
                method.getName(),
                luaCommand.script(),
                luaCommand.validation(),
                resolveAll(luaCommand.keys(), method, args),
                resolveAll(luaCommand.args(), method, args),
                luaCommand.returns()
        );

        if (CommandContext.isActive()) {
            CommandContext.enqueue(queuedCommand);
            log.debug("LuaCommand '{}' queued", queuedCommand.name());
        } else {
            log.debug("LuaCommand '{}' executed standalone (no transaction)", queuedCommand.name());
            executor.executeAll(List.of(queuedCommand));
        }

        return result;
    }

    private List<String> resolveAll(final String[] templates, final Method method, final Object[] args) {
        final List<String> resolved = new ArrayList<>(templates.length);
        for (final String template : templates) {
            appendResolved(resolved, SpelResolver.resolve(template, method, args));
        }
        return resolved;
    }

    private void appendResolved(final List<String> out, final Object value) {
        if (value == null) {
            out.add("");
            return;
        }
        if (value instanceof Collection<?> collection) {
            for (final Object item : collection) {
                out.add(item == null ? "" : item.toString());
            }
            return;
        }
        if (value.getClass().isArray()) {
            final int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                final Object item = Array.get(value, i);
                out.add(item == null ? "" : item.toString());
            }
            return;
        }
        out.add(value.toString());
    }
}
