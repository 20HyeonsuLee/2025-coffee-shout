package coffeeshout.racinggame.infra.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.codec.StringCodec;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RacingGameEventPublisher {

    private final LettuceConnectionFactory connectionFactory;
    private final ChannelTopic racingGameEventTopic;
    private final ObjectMapper objectMapper;

    private StatefulRedisConnection<String, String> sharedConnection;
    private RedisAsyncCommands<String, String> asyncCommands;

    @PostConstruct
    public void init() {
        final RedisClient nativeClient = (RedisClient) connectionFactory.getNativeClient();
        sharedConnection = nativeClient.connect(StringCodec.UTF8);
        asyncCommands = sharedConnection.async();
    }

    @PreDestroy
    public void destroy() {
        if (sharedConnection != null) {
            sharedConnection.close();
        }
    }

    public <T> void publishEvent(final T event) {
        final String message = serialize(event);

        asyncCommands.publish(racingGameEventTopic.getTopic(), message)
                .whenComplete((count, throwable) -> {
                    if (throwable != null) {
                        log.error("레이싱 게임 이벤트 비동기 발행 실패: {}", throwable.getMessage(), throwable);
                    }
                });
    }

    private <T> String serialize(final T event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (final JsonProcessingException e) {
            throw new IllegalArgumentException("레이싱 게임 이벤트 직렬화 실패", e);
        }
    }
}
