package coffeeshout.global.messaging;

import coffeeshout.global.metric.PubSubMetricService;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SnapshotResyncCoordinator {

    private final PubSubMetricService pubSubMetric;
    private final SnapshotResyncPolicy policy;
    private final ConcurrentMap<String, InFlightResync> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ResyncCheckpoint> checkpoints = new ConcurrentHashMap<>();

    @Autowired
    public SnapshotResyncCoordinator(final PubSubMetricService pubSubMetric) {
        this(pubSubMetric, SnapshotResyncPolicy.defaultPolicy());
    }

    SnapshotResyncCoordinator(final PubSubMetricService pubSubMetric, final SnapshotResyncPolicy policy) {
        this.pubSubMetric = pubSubMetric;
        this.policy = policy;
    }

    public SnapshotBroadcastOutcome resync(
            final PubSubEnvelope envelope,
            final Supplier<SnapshotBroadcastOutcome> snapshotBroadcast
    ) {
        if (isCoveredByRecentResync(envelope)) {
            pubSubMetric.recordSnapshotResyncCooldownSkip(envelope.eventType());
            return SnapshotBroadcastOutcome.SKIPPED;
        }

        final InFlightResync candidate = new InFlightResync(envelope.version());
        final InFlightResync existing = inFlight.putIfAbsent(envelope.joinCode(), candidate);
        if (existing != null) {
            existing.requestAtLeast(envelope.version());
            pubSubMetric.recordSnapshotResyncCoalesced(envelope.eventType());
            return existing.join();
        }

        SnapshotBroadcastOutcome outcome = SnapshotBroadcastOutcome.FAILED;
        long coveredVersion = envelope.version();
        try {
            outcome = runUntilLatestCoalescedVersionCovered(envelope, candidate, snapshotBroadcast);
            if (outcome != SnapshotBroadcastOutcome.FAILED) {
                coveredVersion = candidate.requestedVersion();
                rememberResync(envelope.joinCode(), coveredVersion);
            }
            return outcome;
        } finally {
            inFlight.remove(envelope.joinCode(), candidate);
            candidate.complete(outcome);
        }
    }

    private boolean isCoveredByRecentResync(final PubSubEnvelope envelope) {
        if (policy.cooldown().isZero() || policy.cooldown().isNegative() || envelope.version() <= 0) {
            return false;
        }
        final ResyncCheckpoint checkpoint = checkpoints.get(envelope.joinCode());
        return checkpoint != null
                && checkpoint.covers(envelope.version())
                && checkpoint.isAlive(System.nanoTime());
    }

    private SnapshotBroadcastOutcome runUntilLatestCoalescedVersionCovered(
            final PubSubEnvelope envelope,
            final InFlightResync current,
            final Supplier<SnapshotBroadcastOutcome> snapshotBroadcast
    ) {
        long targetVersion = current.requestedVersion();
        SnapshotBroadcastOutcome outcome;
        do {
            outcome = runWithRetry(envelope.eventType(), snapshotBroadcast);
            if (outcome == SnapshotBroadcastOutcome.FAILED) {
                return SnapshotBroadcastOutcome.FAILED;
            }
            final long latestRequestedVersion = current.requestedVersion();
            if (latestRequestedVersion <= targetVersion) {
                return outcome;
            }
            log.debug("coalesced resync 중 더 높은 version 감지: joinCode={}, covered={}, requested={}",
                    envelope.joinCode(), targetVersion, latestRequestedVersion);
            targetVersion = latestRequestedVersion;
        } while (true);
    }

    private SnapshotBroadcastOutcome runWithRetry(
            final String eventType,
            final Supplier<SnapshotBroadcastOutcome> snapshotBroadcast
    ) {
        SnapshotBroadcastOutcome outcome = SnapshotBroadcastOutcome.FAILED;
        for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
            sleepBeforeAttempt(attempt);
            final long startNanos = System.nanoTime();
            pubSubMetric.recordSnapshotRead(eventType);
            try {
                outcome = snapshotBroadcast.get();
            } catch (RuntimeException e) {
                log.warn("snapshot resync broadcast task failed: eventType={}, attempt={}", eventType, attempt, e);
                outcome = SnapshotBroadcastOutcome.FAILED;
            }
            final long durationNanos = System.nanoTime() - startNanos;

            if (outcome == SnapshotBroadcastOutcome.SENT) {
                pubSubMetric.recordSnapshotResync(eventType, durationNanos);
                return outcome;
            }
            if (outcome == SnapshotBroadcastOutcome.SKIPPED) {
                return outcome;
            }
            if (attempt < policy.maxAttempts()) {
                pubSubMetric.recordSnapshotResyncRetry(eventType);
            }
        }
        pubSubMetric.recordSnapshotResyncFailed(eventType);
        return outcome;
    }

    private void sleepBeforeAttempt(final int attempt) {
        final long backoffMillis = attempt == 1 ? 0 : policy.backoff().toMillis() * (attempt - 1L);
        final long jitterMillis = policy.maxJitter().isZero() || policy.maxJitter().isNegative()
                ? 0
                : ThreadLocalRandom.current().nextLong(policy.maxJitter().toMillis() + 1);
        sleep(backoffMillis + jitterMillis);
    }

    private void sleep(final long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void rememberResync(final String joinCode, final long version) {
        if (version <= 0 || policy.cooldown().isZero() || policy.cooldown().isNegative()) {
            return;
        }
        checkpoints.put(joinCode, new ResyncCheckpoint(
                version,
                System.nanoTime() + policy.cooldown().toNanos()
        ));
    }

    private static final class InFlightResync {

        private final AtomicLong requestedVersion;
        private final CompletableFuture<SnapshotBroadcastOutcome> future = new CompletableFuture<>();

        private InFlightResync(final long version) {
            this.requestedVersion = new AtomicLong(version);
        }

        private void requestAtLeast(final long version) {
            requestedVersion.accumulateAndGet(version, Math::max);
        }

        private long requestedVersion() {
            return requestedVersion.get();
        }

        private SnapshotBroadcastOutcome join() {
            return future.join();
        }

        private void complete(final SnapshotBroadcastOutcome outcome) {
            future.complete(outcome);
        }
    }

    private record ResyncCheckpoint(long version, long expiresAtNanos) {

        private boolean covers(final long requestedVersion) {
            return version >= requestedVersion;
        }

        private boolean isAlive(final long nowNanos) {
            return nowNanos <= expiresAtNanos;
        }
    }
}

enum SnapshotBroadcastOutcome {
    SENT,
    SKIPPED,
    FAILED
}

record SnapshotResyncPolicy(
        Duration cooldown,
        int maxAttempts,
        Duration backoff,
        Duration maxJitter
) {

    static SnapshotResyncPolicy defaultPolicy() {
        return new SnapshotResyncPolicy(
                Duration.ofMillis(250),
                3,
                Duration.ofMillis(20),
                Duration.ofMillis(40)
        );
    }

    SnapshotResyncPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        if (cooldown == null || backoff == null || maxJitter == null) {
            throw new IllegalArgumentException("durations must not be null");
        }
    }
}
