package coffeeshout.global.config.redis;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 라이브러리로 이관되지 않은 단일-op Lua 스크립트만 빈으로 등록.
 * create/enter/toggle/update_positions/remove_player 는 {@code RoomLuaCommands}로 이전됨.
 */
@Configuration
public class LuaScriptConfig {

    @Bean
    public RedisScript<Long> claimJoinCodeScript() {
        return loadScript("lua/claim_joincode.lua", Long.class);
    }

    private <T> RedisScript<T> loadScript(final String path, final Class<T> resultType) {
        final DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(resultType);
        return script;
    }
}
