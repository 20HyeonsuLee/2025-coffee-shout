package coffeeshout.global.luacommand.core;

import java.util.List;

/**
 * {@link CompositeLuaBuilder}가 만든 단일 Lua 스크립트와 그에 대응하는 flat KEYS/ARGV.
 */
public record CompositeScript(
        String lua,
        List<String> keys,
        List<String> args
) {
}
