package coffeeshout.global.messaging;

import coffeeshout.room.domain.event.PlayerKickEvent;
import coffeeshout.room.domain.event.PlayerListUpdateEvent;
import coffeeshout.room.domain.event.PlayerReadyEvent;
import coffeeshout.room.domain.event.RoomEventType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

/**
 * Redis Pub/Sub `coffeeshout:events` 채널 구독자.
 * 수신한 envelope을 도메인 이벤트로 복원 후 로컬 ApplicationEventPublisher로 dispatch.
 * 자기 메시지(originInstanceId == selfInstanceId)는 skip하여 중복 dispatch 방지.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@org.springframework.context.annotation.Profile("!test")
public class PubSubSubscriber implements MessageListener {

    private final PubSubEnvelopeSerializer serializer;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final @Qualifier("selfInstanceId") String selfInstanceId;

    @Override
    public void onMessage(final Message message, final byte[] pattern) {
        final String json = new String(message.getBody());
        final PubSubEnvelope envelope = serializer.fromJson(json);

        if (selfInstanceId.equals(envelope.originInstanceId())) {
            log.debug("자기 메시지 skip: eventType={}, joinCode={}", envelope.eventType(), envelope.joinCode());
            return;
        }

        log.debug("Pub/Sub 수신: eventType={}, joinCode={}, origin={}",
                envelope.eventType(), envelope.joinCode(), envelope.originInstanceId());

        dispatchEvent(envelope);
    }

    private void dispatchEvent(final PubSubEnvelope envelope) {
        final RoomEventType type = RoomEventType.valueOf(envelope.eventType());
        switch (type) {
            case PLAYER_LIST_UPDATE -> eventPublisher.publishEvent(
                    new PlayerListUpdateEvent(envelope.joinCode()));
            case PLAYER_READY -> dispatchPlayerReady(envelope);
            case PLAYER_KICK -> dispatchPlayerKick(envelope);
            default -> log.debug("Pub/Sub 이벤트 로컬 dispatch 생략 (원격 dispatch 불필요): {}", type);
        }
    }

    private void dispatchPlayerReady(final PubSubEnvelope envelope) {
        try {
            final JsonNode node = objectMapper.readTree(envelope.payloadJson());
            final String playerName = node.get("playerName").asText();
            final boolean isReady = node.get("isReady").asBoolean();
            eventPublisher.publishEvent(new PlayerReadyEvent(envelope.joinCode(), playerName, isReady));
        } catch (Exception e) {
            log.error("PLAYER_READY 역직렬화 실패: payload={}", envelope.payloadJson(), e);
        }
    }

    private void dispatchPlayerKick(final PubSubEnvelope envelope) {
        try {
            final JsonNode node = objectMapper.readTree(envelope.payloadJson());
            final String playerName = node.get("playerName").asText();
            eventPublisher.publishEvent(new PlayerKickEvent(envelope.joinCode(), playerName));
        } catch (Exception e) {
            log.error("PLAYER_KICK 역직렬화 실패: payload={}", envelope.payloadJson(), e);
        }
    }
}
