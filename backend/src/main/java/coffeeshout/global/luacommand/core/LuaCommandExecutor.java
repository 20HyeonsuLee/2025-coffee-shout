package coffeeshout.global.luacommand.core;

import coffeeshout.global.luacommand.annotation.NoException;
import coffeeshout.global.luacommand.annotation.ReturnCode;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * {@link QueuedCommand} 리스트를 composite Lua로 합쳐 실행하고,
 * 반환 코드를 {@link ReturnCode} 매핑에 따라 예외로 변환한다.
 *
 * <p>{@link LuaCommandMetricListener} 빈이 등록돼 있으면 실행 시간과 반환 코드를 전달한다.
 * 예외가 발생해도 리스너는 호출된다 (returnCode에 null 가능).
 */
@Slf4j
@Component
public class LuaCommandExecutor {

    private static final long SUCCESS = 1L;

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectProvider<LuaCommandMetricListener> metricListeners;

    public LuaCommandExecutor(
            final StringRedisTemplate stringRedisTemplate,
            final ObjectProvider<LuaCommandMetricListener> metricListeners
    ) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.metricListeners = metricListeners;
    }

    public void executeAll(final List<QueuedCommand> commands) {
        if (commands.isEmpty()) {
            return;
        }

        final CompositeScript composite = CompositeLuaBuilder.build(commands);
        log.debug("Executing composite Lua ({} commands, {} keys, {} args)",
                commands.size(), composite.keys().size(), composite.args().size());

        final long start = System.nanoTime();
        Long result = null;
        try {
            result = runScript(composite);
            if (result == SUCCESS) {
                return;
            }
            handleFailure(result, commands);
        } finally {
            notifyListeners(commands, result, System.nanoTime() - start);
        }
    }

    private void notifyListeners(final List<QueuedCommand> commands, final Long result, final long durationNanos) {
        metricListeners.forEach(listener -> {
            try {
                listener.onExecution(commands, result, durationNanos);
            } catch (RuntimeException e) {
                log.warn("MetricListener {} threw", listener.getClass().getSimpleName(), e);
            }
        });
    }

    private Long runScript(final CompositeScript composite) {
        final RedisScript<Long> script = RedisScript.of(composite.lua(), Long.class);
        final Long result = stringRedisTemplate.execute(script, composite.keys(), composite.args().toArray());
        if (result == null) {
            throw new IllegalStateException("Lua composite returned null");
        }
        return result;
    }

    private void handleFailure(final long result, final List<QueuedCommand> commands) {
        final DecodedReturnCode decoded = CompositeLuaBuilder.decodeReturnCode(result);
        final QueuedCommand failedCmd = resolveFailedCommand(decoded, commands);
        throw findMappedException(failedCmd, decoded.originalCode());
    }

    private QueuedCommand resolveFailedCommand(final DecodedReturnCode decoded, final List<QueuedCommand> commands) {
        if (decoded.commandIndex() < 0 || decoded.commandIndex() >= commands.size()) {
            throw new IllegalStateException(
                    "Decoded command index " + decoded.commandIndex()
                            + " out of range (size=" + commands.size() + ")"
            );
        }
        return commands.get(decoded.commandIndex());
    }

    private RuntimeException findMappedException(final QueuedCommand failedCmd, final int originalCode) {
        for (final ReturnCode rc : failedCmd.returns()) {
            if (rc.value() == originalCode) {
                return instantiateException(rc, failedCmd);
            }
        }
        return new IllegalStateException(
                "Unmapped Lua return code " + originalCode + " from command '" + failedCmd.name() + "'"
        );
    }

    private RuntimeException instantiateException(final ReturnCode rc, final QueuedCommand failedCmd) {
        final Class<? extends RuntimeException> exClass = rc.throwsException();
        if (exClass == NoException.class) {
            return new IllegalStateException(
                    "ReturnCode " + rc.value() + " on '" + failedCmd.name()
                            + "' has no exception mapping but return code is non-success"
            );
        }
        return newException(exClass, resolveMessage(rc, failedCmd));
    }

    private String resolveMessage(final ReturnCode rc, final QueuedCommand failedCmd) {
        if (!rc.message().isEmpty()) {
            return rc.message();
        }
        return "Lua command '" + failedCmd.name() + "' failed with code " + rc.value();
    }

    private RuntimeException newException(final Class<? extends RuntimeException> exClass, final String message) {
        return findStringConstructor(exClass)
                .map(ctor -> invoke(ctor, message, exClass))
                .orElseGet(() -> invokeNoArg(exClass));
    }

    private Optional<Constructor<? extends RuntimeException>> findStringConstructor(
            final Class<? extends RuntimeException> exClass
    ) {
        try {
            return Optional.of(exClass.getConstructor(String.class));
        } catch (NoSuchMethodException e) {
            return Optional.empty();
        }
    }

    private RuntimeException invoke(
            final Constructor<? extends RuntimeException> ctor,
            final String message,
            final Class<? extends RuntimeException> exClass
    ) {
        try {
            return ctor.newInstance(message);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot instantiate " + exClass.getName(), e);
        }
    }

    private RuntimeException invokeNoArg(final Class<? extends RuntimeException> exClass) {
        try {
            return exClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot instantiate " + exClass.getName(), e);
        }
    }
}
