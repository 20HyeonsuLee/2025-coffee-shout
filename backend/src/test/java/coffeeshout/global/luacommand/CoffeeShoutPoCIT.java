package coffeeshout.global.luacommand;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import coffeeshout.concurrency.ConcurrencyTestSupport;
import coffeeshout.global.luacommand.CoffeeShoutRoomCommandsPoC.DuplicateNameException;
import coffeeshout.global.luacommand.CoffeeShoutRoomCommandsPoC.RoomFullException;
import coffeeshout.global.luacommand.CoffeeShoutRoomCommandsPoC.RoomNotFoundException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Coffee-Shout joinGuest 시나리오를 라이브러리로 재현한 PoC 테스트.
 * 검증 메서드 3개 + 쓰기 메서드 1개를 서비스에서 조합하여 호출하는 구조.
 */
class CoffeeShoutPoCIT extends ConcurrencyTestSupport {

    private static final int MAX_PLAYERS = 9;

    @Autowired
    private CoffeeShoutRoomServicePoC service;

    @Test
    void 방_없으면_RoomNotFound_예외() {
        assertThatThrownBy(() -> service.joinGuest("ZZZZ", "guest", MAX_PLAYERS, "{}"))
                .isInstanceOf(RoomNotFoundException.class);
    }

    @Test
    void 정원_초과_시_RoomFull_예외_반환_및_상태_유지() {
        stringRedisTemplate.opsForHash().put("room:FULL:meta", "hostName", "host");
        for (int i = 0; i < 9; i++) {
            stringRedisTemplate.opsForSet().add("room:FULL:players", "p" + i);
        }

        assertThatThrownBy(() -> service.joinGuest("FULL", "newbie", MAX_PLAYERS, "{}"))
                .isInstanceOf(RoomFullException.class);

        assertThat(stringRedisTemplate.opsForSet().isMember("room:FULL:players", "newbie")).isFalse();
    }

    @Test
    void 중복_이름_시_DuplicateName_예외() {
        stringRedisTemplate.opsForHash().put("room:DUPN:meta", "hostName", "host");
        stringRedisTemplate.opsForSet().add("room:DUPN:players", "dup");

        assertThatThrownBy(() -> service.joinGuest("DUPN", "dup", MAX_PLAYERS, "{}"))
                .isInstanceOf(DuplicateNameException.class);
    }

    @Test
    void 정상_입장_시_SADD_반영() {
        stringRedisTemplate.opsForHash().put("room:OKOK:meta", "hostName", "host");
        stringRedisTemplate.opsForSet().add("room:OKOK:players", "host");

        service.joinGuest("OKOK", "guest", MAX_PLAYERS, "{\"type\":\"enter\"}");

        assertThat(stringRedisTemplate.opsForSet().isMember("room:OKOK:players", "guest")).isTrue();
        assertThat(stringRedisTemplate.opsForSet().size("room:OKOK:players")).isEqualTo(2L);
    }

    @Test
    void 정원_9명_방에_20명_동시_입장_시_정확히_8명만_성공() throws InterruptedException {
        stringRedisTemplate.opsForHash().put("room:RACE:meta", "hostName", "host");
        stringRedisTemplate.opsForSet().add("room:RACE:players", "host");

        final int threadCount = 20;
        final ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threadCount);
        final AtomicInteger success = new AtomicInteger();
        final AtomicInteger full = new AtomicInteger();
        final AtomicInteger other = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            final String name = "g-" + i;
            executor.submit(() -> {
                try {
                    start.await();
                    service.joinGuest("RACE", name, MAX_PLAYERS, "{}");
                    success.incrementAndGet();
                } catch (RoomFullException e) {
                    full.incrementAndGet();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    other.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(success.get()).isEqualTo(8);
        assertThat(full.get()).isEqualTo(12);
        assertThat(other.get()).isZero();
        assertThat(stringRedisTemplate.opsForSet().size("room:RACE:players")).isEqualTo(9L);
    }
}
