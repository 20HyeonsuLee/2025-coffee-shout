package coffeeshout.racinggame.infra.messaging;

import coffeeshout.global.config.properties.RedisStreamProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.Record;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RacingGameStreamProducer {

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisStreamProperties redisStreamProperties;
    private final ObjectMapper objectMapper;

    public <T> void publishEvent(final T event) {
        final String eventJson = serialize(event);
        final Record<String, String> record = StreamRecords.newRecord()
                .in(redisStreamProperties.racingGameKey())
                .ofObject(eventJson);

        final var recordId = stringRedisTemplate.opsForStream().add(
                record,
                XAddOptions.maxlen(redisStreamProperties.maxLength()).approximateTrimming(true)
        );

        log.debug("레이싱 게임 이벤트 발송: recordId={}, streamKey={}",
                recordId, redisStreamProperties.racingGameKey());
    }

    private <T> String serialize(final T event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (final JsonProcessingException e) {
            throw new IllegalArgumentException("레이싱 게임 이벤트 직렬화 실패", e);
        }
    }
}
