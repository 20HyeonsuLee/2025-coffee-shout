package coffeeshout.global.luacommand.core;

import coffeeshout.global.luacommand.annotation.ReturnCode;
import java.util.List;

/**
 * 트랜잭션 큐에 쌓이는 단일 Lua 명령 스냅샷.
 *
 * <p>{@link coffeeshout.global.luacommand.annotation.LuaCommand} 어노테이션의 정적 메타데이터와
 * 호출 시점에 resolve된 동적 데이터(keys, args)를 담는다.
 */
public record QueuedCommand(
        String name,
        String script,
        boolean validation,
        List<String> keys,
        List<String> args,
        ReturnCode[] returns
) {

    public boolean isValidation() {
        return validation;
    }

    public boolean isMutation() {
        return !validation;
    }
}
