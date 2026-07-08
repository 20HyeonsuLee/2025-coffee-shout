package coffeeshout.global.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import coffeeshout.global.metric.PubSubMetricService;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SnapshotResyncCoordinatorTest {

    private static final String EVENT_TYPE = "PLAYER_READY";
    private static final String JOIN_CODE = "ABCD";

    @Mock
    private PubSubMetricService pubSubMetric;

    @Test
    void 같은_방_같은_version의_동시_resync는_snapshot_read를_한번만_수행한다() throws Exception {
        // given
        final SnapshotResyncCoordinator coordinator = coordinator(Duration.ofSeconds(10), 1);
        final AtomicInteger snapshotReads = new AtomicInteger();
        final CountDownLatch firstReadStarted = new CountDownLatch(1);
        final CountDownLatch secondRequestCoalesced = new CountDownLatch(1);
        final CountDownLatch releaseFirstRead = new CountDownLatch(1);
        final ExecutorService executor = Executors.newFixedThreadPool(2);
        doAnswer(invocation -> {
            secondRequestCoalesced.countDown();
            return null;
        }).when(pubSubMetric).recordSnapshotResyncCoalesced(EVENT_TYPE);

        try {
            final Future<SnapshotBroadcastOutcome> first = executor.submit(() ->
                    coordinator.resync(envelope(3), () -> {
                        snapshotReads.incrementAndGet();
                        firstReadStarted.countDown();
                        await(releaseFirstRead);
                        return SnapshotBroadcastOutcome.SENT;
                    })
            );
            assertThat(firstReadStarted.await(3, TimeUnit.SECONDS)).isTrue();

            final Future<SnapshotBroadcastOutcome> second = executor.submit(() ->
                    coordinator.resync(envelope(3), () -> {
                        snapshotReads.incrementAndGet();
                        return SnapshotBroadcastOutcome.SENT;
                    })
            );

            assertThat(secondRequestCoalesced.await(3, TimeUnit.SECONDS)).isTrue();
            releaseFirstRead.countDown();

            // then
            assertThat(first.get(3, TimeUnit.SECONDS)).isEqualTo(SnapshotBroadcastOutcome.SENT);
            assertThat(second.get(3, TimeUnit.SECONDS)).isEqualTo(SnapshotBroadcastOutcome.SENT);
            assertThat(snapshotReads.get()).isEqualTo(1);
            then(pubSubMetric).should(times(1)).recordSnapshotRead(EVENT_TYPE);
            then(pubSubMetric).should().recordSnapshotResyncCoalesced(EVENT_TYPE);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void coalescing_중_더_높은_version이_합류하면_최신_snapshot을_한번_더_읽는다() throws Exception {
        // given
        final SnapshotResyncCoordinator coordinator = coordinator(Duration.ofSeconds(10), 1);
        final AtomicInteger snapshotReads = new AtomicInteger();
        final CountDownLatch firstReadStarted = new CountDownLatch(1);
        final CountDownLatch secondRequestCoalesced = new CountDownLatch(1);
        final CountDownLatch releaseFirstRead = new CountDownLatch(1);
        final ExecutorService executor = Executors.newFixedThreadPool(2);
        doAnswer(invocation -> {
            secondRequestCoalesced.countDown();
            return null;
        }).when(pubSubMetric).recordSnapshotResyncCoalesced(EVENT_TYPE);

        try {
            final Future<SnapshotBroadcastOutcome> first = executor.submit(() ->
                    coordinator.resync(envelope(3), () -> {
                        final int readCount = snapshotReads.incrementAndGet();
                        if (readCount == 1) {
                            firstReadStarted.countDown();
                            await(releaseFirstRead);
                        }
                        return SnapshotBroadcastOutcome.SENT;
                    })
            );
            assertThat(firstReadStarted.await(3, TimeUnit.SECONDS)).isTrue();

            final Future<SnapshotBroadcastOutcome> second = executor.submit(() ->
                    coordinator.resync(envelope(4), () -> {
                        snapshotReads.incrementAndGet();
                        return SnapshotBroadcastOutcome.SENT;
                    })
            );

            assertThat(secondRequestCoalesced.await(3, TimeUnit.SECONDS)).isTrue();
            releaseFirstRead.countDown();

            // then
            assertThat(first.get(3, TimeUnit.SECONDS)).isEqualTo(SnapshotBroadcastOutcome.SENT);
            assertThat(second.get(3, TimeUnit.SECONDS)).isEqualTo(SnapshotBroadcastOutcome.SENT);
            assertThat(snapshotReads.get()).isEqualTo(2);
            then(pubSubMetric).should(times(2)).recordSnapshotRead(EVENT_TYPE);
            then(pubSubMetric).should().recordSnapshotResyncCoalesced(EVENT_TYPE);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void 이미_같은_version_이상을_복구했다면_cooldown_동안_snapshot_read를_skip한다() {
        // given
        final SnapshotResyncCoordinator coordinator = coordinator(Duration.ofSeconds(10), 1);
        final AtomicInteger snapshotReads = new AtomicInteger();

        // when
        final SnapshotBroadcastOutcome first = coordinator.resync(envelope(3), () -> {
            snapshotReads.incrementAndGet();
            return SnapshotBroadcastOutcome.SENT;
        });
        final SnapshotBroadcastOutcome second = coordinator.resync(envelope(3), () -> {
            snapshotReads.incrementAndGet();
            return SnapshotBroadcastOutcome.SENT;
        });

        // then
        assertThat(first).isEqualTo(SnapshotBroadcastOutcome.SENT);
        assertThat(second).isEqualTo(SnapshotBroadcastOutcome.SKIPPED);
        assertThat(snapshotReads.get()).isEqualTo(1);
        then(pubSubMetric).should(times(1)).recordSnapshotRead(EVENT_TYPE);
        then(pubSubMetric).should().recordSnapshotResyncCooldownSkip(EVENT_TYPE);
    }

    @Test
    void 더_높은_version은_cooldown_중에도_새_snapshot을_읽는다() {
        // given
        final SnapshotResyncCoordinator coordinator = coordinator(Duration.ofSeconds(10), 1);
        final AtomicInteger snapshotReads = new AtomicInteger();

        // when
        coordinator.resync(envelope(3), () -> {
            snapshotReads.incrementAndGet();
            return SnapshotBroadcastOutcome.SENT;
        });
        coordinator.resync(envelope(4), () -> {
            snapshotReads.incrementAndGet();
            return SnapshotBroadcastOutcome.SENT;
        });

        // then
        assertThat(snapshotReads.get()).isEqualTo(2);
        then(pubSubMetric).should(times(2)).recordSnapshotRead(EVENT_TYPE);
        then(pubSubMetric).should(never()).recordSnapshotResyncCooldownSkip(EVENT_TYPE);
    }

    @Test
    void snapshot_read가_실패하면_backoff_retry_후_성공을_기록한다() {
        // given
        final SnapshotResyncCoordinator coordinator = coordinator(Duration.ZERO, 2);
        final AtomicInteger attempts = new AtomicInteger();

        // when
        final SnapshotBroadcastOutcome outcome = coordinator.resync(envelope(3), () -> {
            if (attempts.incrementAndGet() == 1) {
                return SnapshotBroadcastOutcome.FAILED;
            }
            return SnapshotBroadcastOutcome.SENT;
        });

        // then
        assertThat(outcome).isEqualTo(SnapshotBroadcastOutcome.SENT);
        assertThat(attempts.get()).isEqualTo(2);
        then(pubSubMetric).should(times(2)).recordSnapshotRead(EVENT_TYPE);
        then(pubSubMetric).should().recordSnapshotResyncRetry(EVENT_TYPE);
        then(pubSubMetric).should(never()).recordSnapshotResyncFailed(EVENT_TYPE);
    }

    private SnapshotResyncCoordinator coordinator(final Duration cooldown, final int maxAttempts) {
        return new SnapshotResyncCoordinator(
                pubSubMetric,
                new SnapshotResyncPolicy(cooldown, maxAttempts, Duration.ZERO, Duration.ZERO)
        );
    }

    private PubSubEnvelope envelope(final long version) {
        return new PubSubEnvelope(
                "event-" + version,
                EVENT_TYPE,
                JOIN_CODE,
                "{\"joinCode\":\"ABCD\"}",
                version,
                System.currentTimeMillis(),
                "was-b"
        );
    }

    private void await(final CountDownLatch latch) {
        try {
            assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
