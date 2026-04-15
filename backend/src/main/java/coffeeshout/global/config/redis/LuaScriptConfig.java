package coffeeshout.global.config.redis;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Lua 스크립트를 Spring DefaultRedisScript 빈으로 등록.
 * RedisTemplate.execute(script, keys, args) 호출 시 자동으로 EVALSHA + EVAL fallback 처리.
 */
@Configuration
@org.springframework.context.annotation.Profile("!test")
public class LuaScriptConfig {

    @Bean
    public RedisScript<Long> claimJoinCodeScript() {
        return loadScript("lua/claim_joincode.lua", Long.class);
    }

    @Bean
    public RedisScript<Long> createRoomScript() {
        return loadScript("lua/create_room.lua", Long.class);
    }

    @Bean
    public RedisScript<Long> enterRoomScript() {
        return loadScript("lua/enter_room.lua", Long.class);
    }

    @Bean
    public RedisScript<Long> toggleReadyScript() {
        return loadScript("lua/toggle_ready.lua", Long.class);
    }

    @Bean
    public RedisScript<Long> removePlayerScript() {
        return loadScript("lua/remove_player.lua", Long.class);
    }

    private <T> RedisScript<T> loadScript(final String path, final Class<T> resultType) {
        final DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(resultType);
        return script;
    }
}
