package coffeeshout.global.messaging;

import coffeeshout.global.exception.custom.NotExistElementException;
import coffeeshout.global.ui.WebSocketResponse;
import coffeeshout.global.websocket.LoggingSimpMessagingTemplate;
import coffeeshout.room.application.RoomService;
import coffeeshout.room.domain.event.RoomEventType;
import coffeeshout.room.domain.player.Player;
import coffeeshout.room.ui.response.PlayerResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

/**
 * Redis Pub/Sub `coffeeshout:events` 채널 구독자.
 * 원격 WAS에서 발생한 envelope를 받아 로컬 WebSocket 브로드캐스트만 수행한다.
 * 상태 쓰기 핸들러(PlayerReadyEventHandler 등)를 호출하면 Lua PUBLISH -> 재수신 -> 재쓰기 피드백 루프가 생기므로
 * ApplicationEventPublisher 재발행을 하지 않고 읽기+브로드캐스트만 진행한다.
 * 자기 메시지(originInstanceId == selfInstanceId)는 skip하여 이중 브로드캐스트를 방지한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@org.springframework.context.annotation.Profile("!test")
public class PubSubSubscriber implements MessageListener {

    private static final String ROOM_TOPIC_PREFIX = "/topic/room/";

    private final PubSubEnvelopeSerializer serializer;
    private final RoomService roomService;
    private final LoggingSimpMessagingTemplate messagingTemplate;
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

        broadcast(envelope);
    }

    private void broadcast(final PubSubEnvelope envelope) {
        final RoomEventType type = RoomEventType.valueOf(envelope.eventType());
        switch (type) {
            case PLAYER_LIST_UPDATE, PLAYER_READY, PLAYER_KICK -> broadcastPlayerList(envelope.joinCode());
            default -> log.debug("Pub/Sub 이벤트 로컬 브로드캐스트 생략: {}", type);
        }
    }

    private void broadcastPlayerList(final String joinCode) {
        try {
            final List<Player> players = roomService.getPlayersInternal(joinCode);
            final List<PlayerResponse> responses = players.stream()
                    .map(PlayerResponse::from)
                    .toList();
            messagingTemplate.convertAndSend(ROOM_TOPIC_PREFIX + joinCode, WebSocketResponse.success(responses));
        } catch (NotExistElementException e) {
            log.debug("원격 이벤트 브로드캐스트 생략 - 방 없음: joinCode={}", joinCode);
        } catch (Exception e) {
            log.error("원격 이벤트 브로드캐스트 실패: joinCode={}", joinCode, e);
        }
    }
}
