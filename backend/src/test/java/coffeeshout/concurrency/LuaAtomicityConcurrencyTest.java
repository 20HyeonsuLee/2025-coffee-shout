package coffeeshout.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import coffeeshout.global.exception.custom.InvalidStateException;
import coffeeshout.room.domain.JoinCode;
import coffeeshout.room.domain.Room;
import coffeeshout.room.domain.menu.CustomMenu;
import coffeeshout.room.domain.menu.MenuTemperature;
import coffeeshout.room.domain.menu.SelectedMenu;
import coffeeshout.room.domain.player.Player;
import coffeeshout.room.domain.player.PlayerName;
import coffeeshout.room.infra.RedisJoinCodeRepository;
import coffeeshout.room.infra.redis.RedisRoomRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Sprint 3 — Lua 원자성 동시성 테스트.
 * 4개 race scenario를 E2E로 재현하고, MeterRegistry로 수치 검증한다.
 * Testcontainers Redis 7.2를 띄워 실제 Lua EVALSHA를 실행한다.
 */
class LuaAtomicityConcurrencyTest extends ConcurrencyTestSupport {

    private static final Long OK_RESULT = 1L;
    private static final Long DUPLICATE_JOINCODE = 0L;
    private static final Long FULL_RESULT = -1L;
    private static final Long DUPLICATE_NAME = -2L;
    private static final Long REMOVE_NOT_FOUND = 0L;

    @Autowired
    private RedisJoinCodeRepository joinCodeRepository;

    @Autowired
    private RedisRoomRepository roomRepository;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    @DisplayName("joinCode 200개 스레드가 동일 코드 claim 시 단 1개만 성공")
    void joinCode_동시_claim_race() throws InterruptedException {
        // given
        final JoinCode target = new JoinCode("ABCD");
        final int threadCount = 200;
        final ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        final CountDownLatch startGate = new CountDownLatch(1);
        final CountDownLatch doneGate = new CountDownLatch(threadCount);
        final AtomicInteger successCount = new AtomicInteger(0);
        final AtomicInteger failureCount = new AtomicInteger(0);

        final double claimOkBefore = counterCount("lua.script.result.total", "script", "claim_joincode", "result", "1");
        final double claimDupBefore = counterCount("lua.script.result.total", "script", "claim_joincode", "result", "0");

        // when
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    if (joinCodeRepository.save(target)) {
                        successCount.incrementAndGet();
                    } else {
                        failureCount.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneGate.countDown();
                }
            });
        }
        startGate.countDown();
        doneGate.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // then
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failureCount.get()).isEqualTo(threadCount - 1);

        final double claimOkAfter = counterCount("lua.script.result.total", "script", "claim_joincode", "result", "1");
        final double claimDupAfter = counterCount("lua.script.result.total", "script", "claim_joincode", "result", "0");

        assertThat(claimOkAfter - claimOkBefore).isEqualTo(1.0);
        assertThat(claimDupAfter - claimDupBefore).isEqualTo(threadCount - 1);
    }

    @Test
    @DisplayName("정원 9명 방에 20명 동시 입장 시 정확히 8명(host 제외)만 입장 성공")
    void 정원_초과_race() throws InterruptedException {
        // given — host 포함 방 생성 (MAX_PLAYERS=9, host 1 + guest 8)
        final JoinCode joinCode = new JoinCode("FULL");
        createRoomWithHost(joinCode, "host");

        final int threadCount = 20;
        final ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        final CountDownLatch startGate = new CountDownLatch(1);
        final CountDownLatch doneGate = new CountDownLatch(threadCount);
        final AtomicInteger successCount = new AtomicInteger(0);
        final AtomicInteger fullCount = new AtomicInteger(0);
        final AtomicInteger otherFailures = new AtomicInteger(0);

        final double enterOkBefore = counterCount("lua.script.result.total", "script", "enter_room", "result", "1");
        final double enterFullBefore = counterCount("lua.script.result.total", "script", "enter_room", "result", "-1");

        // when — 20명이 동시에 서로 다른 이름으로 입장 시도
        for (int i = 0; i < threadCount; i++) {
            final String guestName = "guest-" + i;
            executor.submit(() -> {
                try {
                    startGate.await();
                    roomRepository.addPlayer(joinCode, buildGuest(guestName));
                    successCount.incrementAndGet();
                } catch (InvalidStateException e) {
                    if (e.getMessage().contains("가득")) {
                        fullCount.incrementAndGet();
                    } else {
                        otherFailures.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    otherFailures.incrementAndGet();
                } finally {
                    doneGate.countDown();
                }
            });
        }
        startGate.countDown();
        doneGate.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // then — host 이미 1명 점유 → 추가 입장 가능 인원은 8명
        assertThat(successCount.get()).isEqualTo(8);
        assertThat(fullCount.get()).isEqualTo(12);
        assertThat(otherFailures.get()).isZero();

        final double enterOkAfter = counterCount("lua.script.result.total", "script", "enter_room", "result", "1");
        final double enterFullAfter = counterCount("lua.script.result.total", "script", "enter_room", "result", "-1");

        assertThat(enterOkAfter - enterOkBefore).isEqualTo(8.0);
        assertThat(enterFullAfter - enterFullBefore).isEqualTo(12.0);
    }

    @Test
    @DisplayName("같은 이름으로 10명 동시 입장 시 1명만 성공")
    void 중복_이름_race() throws InterruptedException {
        // given
        final JoinCode joinCode = new JoinCode("DUPN");
        createRoomWithHost(joinCode, "host");

        final int threadCount = 10;
        final String conflictingName = "dup";
        final ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        final CountDownLatch startGate = new CountDownLatch(1);
        final CountDownLatch doneGate = new CountDownLatch(threadCount);
        final AtomicInteger successCount = new AtomicInteger(0);
        final AtomicInteger duplicateCount = new AtomicInteger(0);

        final double enterOkBefore = counterCount("lua.script.result.total", "script", "enter_room", "result", "1");
        final double enterDupBefore = counterCount("lua.script.result.total", "script", "enter_room", "result", "-2");

        // when
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    roomRepository.addPlayer(joinCode, buildGuest(conflictingName));
                    successCount.incrementAndGet();
                } catch (InvalidStateException e) {
                    if (e.getMessage().contains("중복")) {
                        duplicateCount.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } catch (Exception ignored) {
                    // 기타 예외는 무시
                } finally {
                    doneGate.countDown();
                }
            });
        }
        startGate.countDown();
        doneGate.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // then
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(duplicateCount.get()).isEqualTo(threadCount - 1);

        final double enterOkAfter = counterCount("lua.script.result.total", "script", "enter_room", "result", "1");
        final double enterDupAfter = counterCount("lua.script.result.total", "script", "enter_room", "result", "-2");

        assertThat(enterOkAfter - enterOkBefore).isEqualTo(1.0);
        assertThat(enterDupAfter - enterDupBefore).isEqualTo(threadCount - 1);
    }

    @Test
    @DisplayName("같은 플레이어 10명 동시 제거 시 실제 제거는 1회만 발생 (유령 HSET 방지)")
    void 유령_HSET_race() throws InterruptedException {
        // given — host + guest 1명 구성
        final JoinCode joinCode = new JoinCode("GHST");
        createRoomWithHost(joinCode, "host");
        final String victim = "victim";
        roomRepository.addPlayer(joinCode, buildGuest(victim));

        final int threadCount = 10;
        final PlayerName victimName = new PlayerName(victim);
        final ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        final CountDownLatch startGate = new CountDownLatch(1);
        final CountDownLatch doneGate = new CountDownLatch(threadCount);

        final double removeOkBefore = counterCount("lua.script.result.total", "script", "remove_player", "result", "1");
        final double removeNotFoundBefore = counterCount("lua.script.result.total", "script", "remove_player", "result", "0");

        // when
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    roomRepository.removePlayer(joinCode, victimName);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } catch (Exception ignored) {
                    // remove는 성공/실패와 무관하게 예외를 던지지 않음
                } finally {
                    doneGate.countDown();
                }
            });
        }
        startGate.countDown();
        doneGate.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // then — 실제 제거(result=1)는 1회, 나머지는 유령(result=0)
        final double removeOkAfter = counterCount("lua.script.result.total", "script", "remove_player", "result", "1");
        final double removeNotFoundAfter = counterCount("lua.script.result.total", "script", "remove_player", "result", "0");

        assertThat(removeOkAfter - removeOkBefore).isEqualTo(1.0);
        assertThat(removeNotFoundAfter - removeNotFoundBefore).isEqualTo(threadCount - 1);

        // players Set에서 victim이 제거됐는지 추가 검증
        final Long playersCount = stringRedisTemplate.opsForSet().size("room:GHST:players");
        assertThat(playersCount).isEqualTo(1L); // host만 남음
    }

    private void createRoomWithHost(final JoinCode joinCode, final String hostName) {
        final SelectedMenu menu = new SelectedMenu(new CustomMenu("커스텀", "url"), MenuTemperature.HOT);
        final Room room = Room.createNewRoom(joinCode, new PlayerName(hostName), menu);
        roomRepository.save(room);
    }

    private Player buildGuest(final String name) {
        final SelectedMenu menu = new SelectedMenu(new CustomMenu("커스텀", "url"), MenuTemperature.HOT);
        return Player.createGuest(new PlayerName(name), menu);
    }

    private double counterCount(final String name, final String... keyValueTags) {
        final Search search = meterRegistry.find(name);
        for (int i = 0; i + 1 < keyValueTags.length; i += 2) {
            search.tag(keyValueTags[i], keyValueTags[i + 1]);
        }
        return search.counters().stream()
                .mapToDouble(counter -> counter.count())
                .sum();
    }
}
