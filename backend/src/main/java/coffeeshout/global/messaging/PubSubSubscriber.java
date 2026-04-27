package coffeeshout.global.messaging;

import coffeeshout.global.exception.custom.NotExistElementException;
import coffeeshout.global.metric.PubSubMetricService;
import io.micrometer.observation.annotation.Observed;
import coffeeshout.global.ui.WebSocketResponse;
import coffeeshout.global.websocket.LoggingSimpMessagingTemplate;
import coffeeshout.room.application.RoomService;
import coffeeshout.room.domain.event.RoomEventType;
import coffeeshout.room.domain.player.Player;
import coffeeshout.room.ui.response.PlayerResponse;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

/**
 * Redis Pub/Sub `coffeeshout:events` 채널 구독자.
 * 원격 WAS에서 발생한 envelope를 받아 로컬 WebSocket 브로드캐스트만 수행한다.
 * 상태 쓰기 핸들러(PlayerReadyEventHandler 등)를 호출하면 Redis PUBLISH -> 재수신 -> 재쓰기 피드백 루프가 생기므로
 * ApplicationEventPublisher 재발행을 하지 않고 읽기+브로드캐스트만 진행한다.
 * 자기 메시지(originInstanceId == selfInstanceId)는 skip하여 이중 브로드캐스트를 방지한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PubSubSubscriber implements MessageListener {

    private static final String ROOM_TOPIC_PREFIX = "/topic/room/";

    private final PubSubEnvelopeSerializer serializer;
    private final RoomService roomService;
    private final LoggingSimpMessagingTemplate messagingTemplate;
    private final @Qualifier("selfInstanceId") String selfInstanceId;
    private final PubSubMetricService pubSubMetric;
    private final ConcurrentMap<String, Long> lastSeenVersion = new ConcurrentHashMap<>();

    @Override
    @Observed(name = "pubsub.onMessage")
    public void onMessage(final Message message, final byte[] pattern) {
        final String json = new String(message.getBody());
        final PubSubEnvelope envelope = serializer.fromJson(json);

        pubSubMetric.recordReceive(envelope.eventType());

        if (selfInstanceId.equals(envelope.originInstanceId())) {
            markSeen(envelope);
            log.debug("자기 메시지 skip: eventType={}, joinCode={}, version={}",
                    envelope.eventType(), envelope.joinCode(), envelope.version());
            pubSubMetric.recordSelfSkip();
            return;
        }

        final VersionDecision decision = decide(envelope);
        if (decision == VersionDecision.STALE_OR_DUPLICATE) {
            log.debug("Pub/Sub stale/duplicate drop: eventType={}, joinCode={}, version={}, lastSeen={}, eventId={}",
                    envelope.eventType(), envelope.joinCode(), envelope.version(),
                    lastSeenVersion.getOrDefault(envelope.joinCode(), 0L), envelope.eventId());
            pubSubMetric.recordStaleDrop(envelope.eventType());
            return;
        }

        log.debug("Pub/Sub 수신: eventType={}, joinCode={}, origin={}, version={}, decision={}",
                envelope.eventType(), envelope.joinCode(), envelope.originInstanceId(), envelope.version(), decision);

        pubSubMetric.recordPropagationDelay(envelope.publishedAt(), System.currentTimeMillis());

        if (decision == VersionDecision.GAP) {
            pubSubMetric.recordGapDetected(envelope.eventType());
            final long startNanos = System.nanoTime();
            final BroadcastOutcome outcome = broadcast(envelope);
            if (outcome == BroadcastOutcome.SENT) {
                pubSubMetric.recordSnapshotResync(envelope.eventType(), System.nanoTime() - startNanos);
            }
            if (outcome != BroadcastOutcome.FAILED) {
                markSeen(envelope);
            }
            return;
        }

        if (broadcast(envelope) != BroadcastOutcome.FAILED) {
            markSeen(envelope);
        }
    }

    private VersionDecision decide(final PubSubEnvelope envelope) {
        if (envelope.version() <= 0) {
            return VersionDecision.IN_ORDER;
        }
        final long previousVersion = lastSeenVersion.getOrDefault(envelope.joinCode(), 0L);
        if (envelope.version() <= previousVersion) {
            return VersionDecision.STALE_OR_DUPLICATE;
        }
        if (envelope.version() > previousVersion + 1) {
            return VersionDecision.GAP;
        }
        return VersionDecision.IN_ORDER;
    }

    private void markSeen(final PubSubEnvelope envelope) {
        if (envelope.version() <= 0) {
            return;
        }
        lastSeenVersion.merge(envelope.joinCode(), envelope.version(), Math::max);
    }

    private BroadcastOutcome broadcast(final PubSubEnvelope envelope) {
        final Optional<RoomEventType> type = parseRoomEventType(envelope.eventType());
        if (type.isEmpty()) {
            log.debug("Pub/Sub 이벤트 로컬 브로드캐스트 생략: eventType={}", envelope.eventType());
            return BroadcastOutcome.SKIPPED;
        }

        return switch (type.get()) {
            case PLAYER_LIST_UPDATE, PLAYER_READY, PLAYER_KICK -> broadcastPlayerList(envelope.joinCode());
            default -> {
                log.debug("Pub/Sub 이벤트 로컬 브로드캐스트 생략: {}", type.get());
                yield BroadcastOutcome.SKIPPED;
            }
        };
    }

    private Optional<RoomEventType> parseRoomEventType(final String eventType) {
        try {
            return Optional.of(RoomEventType.valueOf(eventType));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private BroadcastOutcome broadcastPlayerList(final String joinCode) {
        try {
            final List<Player> players = roomService.getPlayersInternal(joinCode);
            final List<PlayerResponse> responses = players.stream()
                    .map(PlayerResponse::from)
                    .toList();
            messagingTemplate.convertAndSend(ROOM_TOPIC_PREFIX + joinCode, WebSocketResponse.success(responses));
            return BroadcastOutcome.SENT;
        } catch (NotExistElementException e) {
            log.debug("원격 이벤트 브로드캐스트 생략 - 방 없음: joinCode={}", joinCode);
            return BroadcastOutcome.SKIPPED;
        } catch (Exception e) {
            log.error("원격 이벤트 브로드캐스트 실패: joinCode={}", joinCode, e);
            return BroadcastOutcome.FAILED;
        }
    }

    private enum VersionDecision {
        IN_ORDER,
        GAP,
        STALE_OR_DUPLICATE
    }

    private enum BroadcastOutcome {
        SENT,
        SKIPPED,
        FAILED
    }
}
