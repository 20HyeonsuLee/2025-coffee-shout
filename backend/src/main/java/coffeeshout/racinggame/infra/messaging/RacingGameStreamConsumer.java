package coffeeshout.racinggame.infra.messaging;

import coffeeshout.global.config.properties.RedisStreamProperties;
import coffeeshout.racinggame.domain.event.RacingGameEventType;
import coffeeshout.racinggame.domain.event.StartRacingGameCommandEvent;
import coffeeshout.racinggame.domain.event.TapCommandEvent;
import coffeeshout.racinggame.infra.messaging.handler.RacingGameEventHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RacingGameStreamConsumer implements StreamListener<String, MapRecord<String, String, String>> {

    private final Map<RacingGameEventType, RacingGameEventHandler<?>> handlers;
    private final StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private final RedisStreamProperties redisStreamProperties;
    private final ObjectMapper objectMapper;

    public RacingGameStreamConsumer(
            final List<RacingGameEventHandler<?>> handlers,
            @Qualifier("racingGameStreamContainer")
            final StreamMessageListenerContainer<String, MapRecord<String, String, String>> container,
            final RedisStreamProperties redisStreamProperties,
            final ObjectMapper objectMapper
    ) {
        this.handlers = handlers.stream().collect(Collectors.toMap(
                RacingGameEventHandler::getSupportedEventType,
                handler -> handler
        ));
        this.container = container;
        this.redisStreamProperties = redisStreamProperties;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void registerListener() {
        container.receive(
                StreamOffset.fromStart(redisStreamProperties.racingGameKey()),
                this
        );
        log.info("레이싱 게임 스트림 리스너 등록 완료: {}", redisStreamProperties.racingGameKey());
    }

    @Override
    public void onMessage(final MapRecord<String, String, String> message) {
        try {
            final String body = message.getValue().get("payload");
            final RacingGameEventType eventType = extractEventType(body);

            if (!handlers.containsKey(eventType)) {
                log.warn("처리할 수 없는 레이싱 게임 이벤트 타입: {}", eventType);
                return;
            }

            final Object event = deserializeEvent(body, eventType);
            @SuppressWarnings("unchecked")
            final RacingGameEventHandler<Object> handler =
                    (RacingGameEventHandler<Object>) handlers.get(eventType);
            handler.handle(event);
        } catch (Exception e) {
            log.error("레이싱 게임 스트림 메시지 처리 실패: messageId={}, error={}",
                    message.getId(), e.getMessage(), e);
        }
    }

    private RacingGameEventType extractEventType(final String body) throws Exception {
        final JsonNode jsonNode = objectMapper.readTree(body);
        final String eventTypeStr = jsonNode.get("eventType").asText();
        return RacingGameEventType.valueOf(eventTypeStr);
    }

    private Object deserializeEvent(final String body, final RacingGameEventType eventType)
            throws Exception {
        return switch (eventType) {
            case START_RACING_GAME_COMMAND -> objectMapper.readValue(body, StartRacingGameCommandEvent.class);
            case TAP_COMMAND -> objectMapper.readValue(body, TapCommandEvent.class);
        };
    }
}
