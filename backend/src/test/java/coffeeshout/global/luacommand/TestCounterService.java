package coffeeshout.global.luacommand;

import coffeeshout.global.luacommand.annotation.RedisTransactional;
import org.springframework.stereotype.Service;

@Service
public class TestCounterService {

    private final TestCounterComponent counter;

    public TestCounterService(final TestCounterComponent counter) {
        this.counter = counter;
    }

    @RedisTransactional
    public void batchIncrement(final String key, final int count) {
        counter.checkCanIncrementBy(key, count);
        for (int i = 0; i < count; i++) {
            counter.increment(key);
        }
    }

    @RedisTransactional
    public void setIfDifferentAndIncrementCounter(final String key, final String value) {
        counter.checkNotSameValue(key, value);
        counter.set(key, value);
        counter.increment(key + ":counter");
    }
}
