package coffeeshout.global.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import coffeeshout.global.metric.PubSubMetricService;
import coffeeshout.global.websocket.LoggingSimpMessagingTemplate;
import coffeeshout.room.application.RoomService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.Message;

@ExtendWith(MockitoExtension.class)
class PubSubSubscriberVersionTest {

    private static final String SELF_INSTANCE_ID = "was-a";
    private static final String REMOTE_INSTANCE_ID = "was-b";
    private static final String JOIN_CODE = "ABCD";
    private static final String BODY = "{}";

    @Mock
    private PubSubEnvelopeSerializer serializer;

    @Mock
    private RoomService roomService;

    @Mock
    private LoggingSimpMessagingTemplate messagingTemplate;

    @Mock
    private PubSubMetricService pubSubMetric;

    private PubSubSubscriber subscriber;

    @BeforeEach
    void setUp() {
        subscriber = new PubSubSubscriber(serializer, roomService, messagingTemplate, SELF_INSTANCE_ID, pubSubMetric);
    }

    @Test
    void 순서대로_도착한_원격_메시지는_스냅샷을_브로드캐스트한다() {
        // given
        given(serializer.fromJson(BODY)).willReturn(remoteEnvelope("event-1", 1));
        given(roomService.getPlayersInternal(JOIN_CODE)).willReturn(List.of());

        // when
        subscriber.onMessage(redisMessage(), null);

        // then
        then(messagingTemplate).should()
                .convertAndSend(eq("/topic/room/" + JOIN_CODE), any());
        then(pubSubMetric).should().recordReceive("PLAYER_READY");
        then(pubSubMetric).should().recordPropagationDelay(anyLong(), anyLong());
        then(pubSubMetric).should(never()).recordStaleDrop(anyString());
    }

    @Test
    void 이미_처리한_version은_중복_또는_역전_메시지로_drop한다() {
        // given
        final PubSubEnvelope envelope = remoteEnvelope("event-1", 1);
        given(serializer.fromJson(BODY)).willReturn(envelope, envelope);
        given(roomService.getPlayersInternal(JOIN_CODE)).willReturn(List.of());
        final Message message = redisMessage();

        // when
        subscriber.onMessage(message, null);
        subscriber.onMessage(message, null);

        // then
        then(messagingTemplate).should(times(1))
                .convertAndSend(eq("/topic/room/" + JOIN_CODE), any());
        then(pubSubMetric).should().recordStaleDrop("PLAYER_READY");
    }

    @Test
    void version_gap이_보이면_스냅샷을_다시_읽어_브로드캐스트하고_resync를_기록한다() {
        // given
        given(serializer.fromJson(BODY)).willReturn(
                remoteEnvelope("event-1", 1),
                remoteEnvelope("event-3", 3)
        );
        given(roomService.getPlayersInternal(JOIN_CODE)).willReturn(List.of());
        final Message message = redisMessage();

        // when
        subscriber.onMessage(message, null);
        subscriber.onMessage(message, null);

        // then
        then(messagingTemplate).should(times(2))
                .convertAndSend(eq("/topic/room/" + JOIN_CODE), any());
        then(pubSubMetric).should().recordGapDetected("PLAYER_READY");
        then(pubSubMetric).should().recordSnapshotResync(eq("PLAYER_READY"), anyLong());
    }

    @Test
    void 자기_메시지는_skip하지만_version은_처리한_것으로_기록한다() {
        // given
        given(serializer.fromJson(BODY)).willReturn(
                selfEnvelope("event-5", 5),
                remoteEnvelope("event-4", 4)
        );
        final Message message = redisMessage();

        // when
        subscriber.onMessage(message, null);
        subscriber.onMessage(message, null);

        // then
        then(messagingTemplate).should(never()).convertAndSend(anyString(), any());
        then(pubSubMetric).should().recordSelfSkip();
        then(pubSubMetric).should().recordStaleDrop("PLAYER_READY");
    }

    @Test
    void room_event가_아닌_이벤트도_version_처리_중_예외를_내지_않는다() {
        // given
        given(serializer.fromJson(BODY)).willReturn(new PubSubEnvelope(
                "event-racing-1",
                "RACING_POSITIONS",
                JOIN_CODE,
                "{\"joinCode\":\"ABCD\"}",
                1,
                System.currentTimeMillis(),
                REMOTE_INSTANCE_ID
        ));

        // when
        subscriber.onMessage(redisMessage(), null);

        // then
        then(messagingTemplate).should(never()).convertAndSend(anyString(), any());
        then(pubSubMetric).should().recordReceive("RACING_POSITIONS");
    }

    private Message redisMessage() {
        final Message message = mock(Message.class);
        given(message.getBody()).willReturn(BODY.getBytes(StandardCharsets.UTF_8));
        return message;
    }

    private PubSubEnvelope remoteEnvelope(final String eventId, final long version) {
        return envelope(eventId, version, REMOTE_INSTANCE_ID);
    }

    private PubSubEnvelope selfEnvelope(final String eventId, final long version) {
        return envelope(eventId, version, SELF_INSTANCE_ID);
    }

    private PubSubEnvelope envelope(final String eventId, final long version, final String originInstanceId) {
        return new PubSubEnvelope(
                eventId,
                "PLAYER_READY",
                JOIN_CODE,
                "{\"joinCode\":\"ABCD\"}",
                version,
                System.currentTimeMillis(),
                originInstanceId
        );
    }
}
