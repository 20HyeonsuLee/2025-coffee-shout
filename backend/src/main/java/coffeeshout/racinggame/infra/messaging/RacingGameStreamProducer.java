package coffeeshout.racinggame.infra.messaging;

import coffeeshout.global.config.properties.RedisStreamProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.codec.StringCodec;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RacingGameStreamProducer {

    private final LettuceConnectionFactory connectionFactory;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedisStreamProperties redisStreamProperties;
    private final ObjectMapper objectMapper;
    private final boolean asyncEnabled;

    private StatefulRedisConnection<String, String> sharedConnection;
    private RedisAsyncCommands<String, String> asyncCommands;

    public RacingGameStreamProducer(
            final LettuceConnectionFactory connectionFactory,
            final StringRedisTemplate stringRedisTemplate,
            final RedisStreamProperties redisStreamProperties,
            final ObjectMapper objectMapper,
            @Value("${racing-game.stream.async:false}") final boolean asyncEnabled
    ) {
        this.connectionFactory = connectionFactory;
        this.stringRedisTemplate = stringRedisTemplate;
        this.redisStreamProperties = redisStreamProperties;
        this.objectMapper = objectMapper;
        this.asyncEnabled = asyncEnabled;
    }

    @PostConstruct
    public void init() {
        if (asyncEnabled) {
            final RedisClient nativeClient = (RedisClient) connectionFactory.getNativeClient();
            sharedConnection = nativeClient.connect(StringCodec.UTF8);
            asyncCommands = sharedConnection.async();
            log.info("RacingGameStreamProducer: async 모드 활성화");
        } else {
            log.info("RacingGameStreamProducer: blocking 모드 활성화");
        }
    }

    @PreDestroy
    public void destroy() {
        if (sharedConnection != null) {
            sharedConnection.close();
        }
    }

    public <T> void publishEvent(final T event) {
        if (asyncEnabled) {
            publishAsync(event);
        } else {
            publishBlocking(event);
        }
    }

    private <T> void publishAsync(final T event) {
        final String eventJson = serialize(event);
        final String streamKey = redisStreamProperties.racingGameKey();
        final XAddArgs xAddArgs = new XAddArgs()
                .maxlen(redisStreamProperties.maxLength())
                .approximateTrimming();

        asyncCommands.xadd(streamKey, xAddArgs, "payload", eventJson)
                .whenComplete((messageId, throwable) -> {
                });
    }

    private <T> void publishBlocking(final T event) {
        final String eventJson = serialize(event);
        final String streamKey = redisStreamProperties.racingGameKey();

        stringRedisTemplate.opsForStream().add(
                StreamRecords.newRecord().in(streamKey).ofMap(java.util.Map.of("payload", eventJson)),
                RedisStreamCommands.XAddOptions.maxlen(redisStreamProperties.maxLength()).approximateTrimming(true)
        );
    }

    private <T> String serialize(final T event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (final JsonProcessingException e) {
            throw new IllegalArgumentException("레이싱 게임 이벤트 직렬화 실패", e);
        }
    }
}
