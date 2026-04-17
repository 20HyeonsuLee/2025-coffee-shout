package coffeeshout.global.luacommand.core;

import java.util.List;

/**
 * Composite Lua 실행 후 호출되는 관측 훅. 여러 구현이 있으면 모두 순차 호출된다.
 *
 * <p>예외 발생 시에도 {@code returnCode}에 Lua 반환값(또는 null)을 담아 호출된다.
 */
public interface LuaCommandMetricListener {

    void onExecution(List<QueuedCommand> commands, Long returnCode, long durationNanos);
}
