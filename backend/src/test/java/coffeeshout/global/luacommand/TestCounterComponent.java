package coffeeshout.global.luacommand;

import coffeeshout.global.luacommand.annotation.LuaCommand;
import coffeeshout.global.luacommand.annotation.ReturnCode;
import org.springframework.stereotype.Component;

/**
 * 통합 테스트용 샘플 컴포넌트. 검증/쓰기 메서드가 각각 분리된 새 스키마 검증용.
 */
@Component
public class TestCounterComponent {

    // ---- 쓰기 명령 ----

    @LuaCommand(
            script = "redis.call('INCR', KEYS[1])",
            keys = {"#{#a0}"}
    )
    public void increment(final String key) {
    }

    @LuaCommand(
            script = "redis.call('SET', KEYS[1], ARGV[1])",
            keys = {"#{#a0}"},
            args = {"#{#a1}"}
    )
    public void set(final String key, final String value) {
    }

    // ---- 검증 명령 ----

    @LuaCommand(
            validation = true,
            script = """
                    local current = tonumber(redis.call('GET', KEYS[1]) or '0')
                    if current + tonumber(ARGV[1]) > 10 then return -1 end
                    """,
            keys = {"#{#a0}"},
            args = {"#{#a1}"},
            returns = {
                    @ReturnCode(value = -1, throwsException = IllegalStateException.class, message = "Would exceed 10")
            }
    )
    public void checkCanIncrementBy(final String key, final int delta) {
    }

    @LuaCommand(
            validation = true,
            script = "if redis.call('GET', KEYS[1]) == ARGV[1] then return -1 end",
            keys = {"#{#a0}"},
            args = {"#{#a1}"},
            returns = {
                    @ReturnCode(value = -1, throwsException = IllegalStateException.class, message = "Already set")
            }
    )
    public void checkNotSameValue(final String key, final String value) {
    }
}
