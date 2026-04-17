package coffeeshout.global.luacommand;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import coffeeshout.concurrency.ConcurrencyTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Lua Command 라이브러리 통합 테스트.
 * Testcontainers Redis 기반. 어노테이션 + AOP + composite Lua 실행 전체 흐름 검증.
 */
class LuaCommandLibraryIT extends ConcurrencyTestSupport {

    @Autowired
    private TestCounterComponent counter;

    @Autowired
    private TestCounterService service;

    @Test
    void 단일_LuaCommand_호출은_즉시_실행된다() {
        counter.increment("counter:single");

        assertThat(stringRedisTemplate.opsForValue().get("counter:single")).isEqualTo("1");
    }

    @Test
    void RedisTransactional_안에서_여러_명령이_하나의_composite으로_실행된다() {
        service.batchIncrement("counter:batch", 3);

        assertThat(stringRedisTemplate.opsForValue().get("counter:batch")).isEqualTo("3");
    }

    @Test
    void 검증_실패_시_매핑된_예외가_던져지고_mutation은_실행되지_않는다() {
        stringRedisTemplate.opsForValue().set("counter:overflow", "9");

        assertThatThrownBy(() -> service.batchIncrement("counter:overflow", 5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Would exceed 10");

        // mutation이 실행되지 않아야 함 - 원본 값 유지
        assertThat(stringRedisTemplate.opsForValue().get("counter:overflow")).isEqualTo("9");
    }

    @Test
    void 검증_명령과_쓰기_명령이_혼합되어_실행된다() {
        service.setIfDifferentAndIncrementCounter("key:mixed", "value-A");

        assertThat(stringRedisTemplate.opsForValue().get("key:mixed")).isEqualTo("value-A");
        assertThat(stringRedisTemplate.opsForValue().get("key:mixed:counter")).isEqualTo("1");
    }

    @Test
    void 검증_명령이_실패하면_뒤의_쓰기_명령도_실행되지_않는다() {
        stringRedisTemplate.opsForValue().set("key:dup", "same-value");

        assertThatThrownBy(() -> service.setIfDifferentAndIncrementCounter("key:dup", "same-value"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Already set");

        // set 호출이 안 나감 — 원본 값 유지
        assertThat(stringRedisTemplate.opsForValue().get("key:dup")).isEqualTo("same-value");
        // 뒤따라오는 increment도 실행되지 않음
        assertThat(stringRedisTemplate.opsForValue().get("key:dup:counter")).isNull();
    }
}
