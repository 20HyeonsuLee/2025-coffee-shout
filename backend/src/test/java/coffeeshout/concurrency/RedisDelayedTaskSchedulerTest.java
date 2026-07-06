package coffeeshout.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import coffeeshout.global.metric.SchedulerMetricService;
import coffeeshout.global.scheduler.ConsumedDelayedTask;
import coffeeshout.global.scheduler.DelayedTask;
import coffeeshout.global.scheduler.DelayedTaskType;
import coffeeshout.global.scheduler.RedisDelayedTaskScheduler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Redis Sorted Set 스케줄러의 원자적 소비 검증.
 *
 * <p>운영 폴러와 키가 겹치지 않도록 별도 key prefix를 사용한다.
 */
class RedisDelayedTaskSchedulerTest extends ConcurrencyTestSupport {

    private static final String TEST_KEY_PREFIX = "test-scheduler";

    private RedisDelayedTaskScheduler scheduler;

    @BeforeEach
    void setUpScheduler() {
        scheduler = new RedisDelayedTaskScheduler(
                stringRedisTemplate,
                new SchedulerMetricService(new SimpleMeterRegistry()),
                TEST_KEY_PREFIX
        );
    }

    private DelayedTask removalTask(final String playerKey) {
        return new DelayedTask(DelayedTaskType.PLAYER_REMOVAL, playerKey, "payload-" + playerKey);
    }

    @Nested
    class 소비_시점 {

        @Test
        void 실행_시각_전에는_소비되지_않는다() {
            final Instant now = Instant.now();
            scheduler.schedule(removalTask("ABC23:김철수"), now.plusSeconds(60));

            final List<ConsumedDelayedTask> consumed = scheduler.consumeDue(now, 10);

            assertThat(consumed).isEmpty();
        }

        @Test
        void 실행_시각이_지나면_payload와_함께_소비된다() {
            final Instant now = Instant.now();
            scheduler.schedule(removalTask("ABC23:김철수"), now.minusSeconds(1));

            final List<ConsumedDelayedTask> consumed = scheduler.consumeDue(now, 10);

            assertThat(consumed).hasSize(1);
            assertThat(consumed.get(0).type()).isEqualTo(DelayedTaskType.PLAYER_REMOVAL);
            assertThat(consumed.get(0).taskId()).isEqualTo("ABC23:김철수");
            assertThat(consumed.get(0).payload()).isEqualTo("payload-ABC23:김철수");
        }

        @Test
        void 같은_작업은_두_번_소비되지_않는다() {
            final Instant now = Instant.now();
            scheduler.schedule(removalTask("ABC23:김철수"), now.minusSeconds(1));

            final List<ConsumedDelayedTask> first = scheduler.consumeDue(now, 10);
            final List<ConsumedDelayedTask> second = scheduler.consumeDue(now, 10);

            assertThat(first).hasSize(1);
            assertThat(second).isEmpty();
        }

        @Test
        void 소비_후_payload_키도_정리된다() {
            final Instant now = Instant.now();
            scheduler.schedule(removalTask("ABC23:김철수"), now.minusSeconds(1));

            scheduler.consumeDue(now, 10);

            assertThat(stringRedisTemplate.keys(TEST_KEY_PREFIX + ":payload:*")).isEmpty();
        }
    }

    @Nested
    class 취소와_재등록 {

        @Test
        void 취소된_작업은_소비되지_않는다() {
            final Instant now = Instant.now();
            scheduler.schedule(removalTask("ABC23:김철수"), now.minusSeconds(1));

            scheduler.cancel(DelayedTaskType.PLAYER_REMOVAL, "ABC23:김철수");
            final List<ConsumedDelayedTask> consumed = scheduler.consumeDue(now, 10);

            assertThat(consumed).isEmpty();
            assertThat(stringRedisTemplate.keys(TEST_KEY_PREFIX + ":payload:*")).isEmpty();
        }

        @Test
        void 같은_id로_재등록하면_실행_시각과_payload가_덮어써진다() {
            final Instant now = Instant.now();
            scheduler.schedule(removalTask("ABC23:김철수"), now.plusSeconds(60));
            scheduler.schedule(
                    new DelayedTask(DelayedTaskType.PLAYER_REMOVAL, "ABC23:김철수", "새-payload"),
                    now.minusSeconds(1)
            );

            final List<ConsumedDelayedTask> consumed = scheduler.consumeDue(now, 10);

            assertThat(consumed).hasSize(1);
            assertThat(consumed.get(0).payload()).isEqualTo("새-payload");
        }
    }

    @Nested
    class 분산_환경 {

        @Test
        void 다른_인스턴스가_등록한_작업도_소비할_수_있다() {
            // WAS-1이 등록하고 죽은 뒤 WAS-2의 폴러가 가져가는 상황
            final RedisDelayedTaskScheduler was1 = new RedisDelayedTaskScheduler(
                    stringRedisTemplate, new SchedulerMetricService(new SimpleMeterRegistry()), TEST_KEY_PREFIX);
            final RedisDelayedTaskScheduler was2 = new RedisDelayedTaskScheduler(
                    stringRedisTemplate, new SchedulerMetricService(new SimpleMeterRegistry()), TEST_KEY_PREFIX);

            final Instant now = Instant.now();
            was1.schedule(removalTask("ABC23:김철수"), now.minusSeconds(1));

            final List<ConsumedDelayedTask> consumed = was2.consumeDue(now, 10);

            assertThat(consumed).hasSize(1);
        }

        @Test
        void 폴러_여러_개가_동시에_경쟁해도_중복_소비가_없다() throws InterruptedException {
            final int taskCount = 40;
            final int pollerCount = 4;
            final Instant now = Instant.now();

            for (int i = 0; i < taskCount; i++) {
                scheduler.schedule(removalTask("ROOM" + i + ":플레이어" + i), now.minusSeconds(1));
            }

            final List<ConsumedDelayedTask> allConsumed = new CopyOnWriteArrayList<>();
            final CountDownLatch ready = new CountDownLatch(pollerCount);
            final CountDownLatch start = new CountDownLatch(1);
            final CountDownLatch done = new CountDownLatch(pollerCount);
            final ExecutorService executor = Executors.newFixedThreadPool(pollerCount);

            for (int i = 0; i < pollerCount; i++) {
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        allConsumed.addAll(scheduler.consumeDue(now, taskCount));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            ready.await();
            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            assertThat(allConsumed).hasSize(taskCount);
            assertThat(allConsumed.stream().map(ConsumedDelayedTask::taskKey).distinct()).hasSize(taskCount);
        }
    }
}
