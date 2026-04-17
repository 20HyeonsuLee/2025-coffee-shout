package coffeeshout.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import coffeeshout.global.exception.custom.InvalidStateException;
import coffeeshout.room.application.RoomService;
import coffeeshout.room.domain.JoinCode;
import coffeeshout.room.domain.Room;
import coffeeshout.room.domain.player.PlayerName;
import coffeeshout.room.domain.repository.RoomRepository;
import coffeeshout.room.infra.RedisJoinCodeRepository;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Sprint 3 — 동시성 원자성 테스트.
 * Redisson RLock 기반 원자성이 동시 요청에서 올바르게 동작하는지 검증.
 * Testcontainers Redis 7.2 위에서 실행.
 */
class LuaAtomicityConcurrencyTest extends ConcurrencyTestSupport {

    @Autowired
    private RedisJoinCodeRepository joinCodeRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private RoomService roomService;

    @Test
    @DisplayName("joinCode 200개 스레드가 동일 코드 claim 시 단 1개만 성공")
    void joinCode_동시_claim_race() throws InterruptedException {
        final JoinCode target = new JoinCode("ABCD");
        final int threadCount = 200;
        final ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        final CountDownLatch startGate = new CountDownLatch(1);
        final CountDownLatch doneGate = new CountDownLatch(threadCount);
        final AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    if (joinCodeRepository.save(target)) {
                        successCount.incrementAndGet();
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

        assertThat(successCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("정원 9명 방에 20명 동시 입장 시 정확히 8명(host 제외)만 입장 성공")
    void 정원_초과_race() throws InterruptedException {
        final JoinCode joinCode = new JoinCode("FULL");
        createRoomWithHost(joinCode, "host");

        final int threadCount = 20;
        final ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        final CountDownLatch startGate = new CountDownLatch(1);
        final CountDownLatch doneGate = new CountDownLatch(threadCount);
        final AtomicInteger successCount = new AtomicInteger(0);
        final AtomicInteger fullCount = new AtomicInteger(0);
        final AtomicInteger otherFailures = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final String guestName = "guest-" + i;
            executor.submit(() -> {
                try {
                    startGate.await();
                    roomService.enterRoom(joinCode.getValue(), guestName);
                    successCount.incrementAndGet();
                } catch (InvalidStateException e) {
                    if (e.getMessage().contains("가득") || e.getMessage().contains("9명")) {
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

        assertThat(successCount.get()).isEqualTo(8);
        assertThat(fullCount.get()).isEqualTo(12);
        assertThat(otherFailures.get()).isZero();

        final Long playersCount = stringRedisTemplate.opsForSet().size("room:FULL:players");
        assertThat(playersCount).isEqualTo(9L);
    }

    @Test
    @DisplayName("같은 이름으로 10명 동시 입장 시 1명만 성공")
    void 중복_이름_race() throws InterruptedException {
        final JoinCode joinCode = new JoinCode("DUPN");
        createRoomWithHost(joinCode, "host");

        final int threadCount = 10;
        final String conflictingName = "dup";
        final ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        final CountDownLatch startGate = new CountDownLatch(1);
        final CountDownLatch doneGate = new CountDownLatch(threadCount);
        final AtomicInteger successCount = new AtomicInteger(0);
        final AtomicInteger duplicateCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    roomService.enterRoom(joinCode.getValue(), conflictingName);
                    successCount.incrementAndGet();
                } catch (InvalidStateException e) {
                    if (e.getMessage().contains("중복") || e.getMessage().contains("닉네임")) {
                        duplicateCount.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } catch (Exception ignored) {
                }
                finally {
                    doneGate.countDown();
                }
            });
        }
        startGate.countDown();
        doneGate.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(duplicateCount.get()).isEqualTo(threadCount - 1);
    }

    @Test
    @DisplayName("같은 플레이어 10명 동시 제거 시 실제 제거는 1회만 발생")
    void 유령_HSET_race() throws InterruptedException {
        final JoinCode joinCode = new JoinCode("GHST");
        createRoomWithHost(joinCode, "host");
        roomService.enterRoom(joinCode.getValue(), "victim");

        final int threadCount = 10;
        final ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        final CountDownLatch startGate = new CountDownLatch(1);
        final CountDownLatch doneGate = new CountDownLatch(threadCount);
        final AtomicInteger removedCount = new AtomicInteger(0);
        final AtomicInteger notRemovedCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    final boolean removed = roomService.removePlayer(joinCode.getValue(), "victim");
                    if (removed) {
                        removedCount.incrementAndGet();
                    } else {
                        notRemovedCount.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } catch (Exception ignored) {
                } finally {
                    doneGate.countDown();
                }
            });
        }
        startGate.countDown();
        doneGate.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(removedCount.get()).isEqualTo(1);
        assertThat(notRemovedCount.get()).isEqualTo(threadCount - 1);

        final Long playersCount = stringRedisTemplate.opsForSet().size("room:GHST:players");
        assertThat(playersCount).isEqualTo(1L);
    }

    private void createRoomWithHost(final JoinCode joinCode, final String hostName) {
        final Room room = Room.createNewRoom(joinCode, new PlayerName(hostName));
        roomRepository.save(room);
    }
}
