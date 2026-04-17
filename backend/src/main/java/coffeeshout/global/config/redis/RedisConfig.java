package coffeeshout.global.config.redis;

import coffeeshout.global.config.properties.RedisProperties;
import io.lettuce.core.api.StatefulConnection;
import io.lettuce.core.metrics.MicrometerCommandLatencyRecorder;
import io.lettuce.core.metrics.MicrometerOptions;
import io.lettuce.core.resource.ClientResources;
import io.micrometer.core.instrument.MeterRegistry;
import coffeeshout.global.messaging.PubSubSubscriber;
import lombok.RequiredArgsConstructor;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
@RequiredArgsConstructor
public class RedisConfig {

    private final RedisProperties redisProperties;

    @Bean(destroyMethod = "shutdown")
    public ClientResources lettuceClientResources(final MeterRegistry meterRegistry) {
        final MicrometerOptions options = MicrometerOptions.builder()
                .enable()
                .histogram(true)
                .build();

        return ClientResources.builder()
                .commandLatencyRecorder(new MicrometerCommandLatencyRecorder(meterRegistry, options))
                .build();
    }

    @Bean
    public RedisConnectionFactory redisConnectionFactory(final ClientResources clientResources) {
        final RedisStandaloneConfiguration config =
                new RedisStandaloneConfiguration(redisProperties.host(), redisProperties.port());

        final GenericObjectPoolConfig<StatefulConnection<?, ?>> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(8);
        poolConfig.setMaxIdle(8);
        poolConfig.setMinIdle(2);

        final LettucePoolingClientConfiguration clientConfig = buildClientConfig(poolConfig, clientResources);
        return new LettuceConnectionFactory(config, clientConfig);
    }

    private LettucePoolingClientConfiguration buildClientConfig(
            final GenericObjectPoolConfig<StatefulConnection<?, ?>> poolConfig,
            final ClientResources clientResources
    ) {
        final var builder = LettucePoolingClientConfiguration.builder()
                .poolConfig(poolConfig)
                .clientResources(clientResources);

        if (redisProperties.ssl().enabled()) {
            builder.useSsl();
        }

        return builder.build();
    }

    @Bean
    public RedisTemplate<String, String> redisTemplate(final RedisConnectionFactory connectionFactory) {
        final RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(final RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        final Config config = new Config();
        final String address = "redis://" + redisProperties.host() + ":" + redisProperties.port();
        config.useSingleServer().setAddress(address);
        return Redisson.create(config);
    }

    @Bean
    public ChannelTopic coffeeShoutEventsTopic() {
        return new ChannelTopic("coffeeshout:events");
    }

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            final RedisConnectionFactory connectionFactory,
            final PubSubSubscriber subscriber,
            final ChannelTopic coffeeShoutEventsTopic
    ) {
        final RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, coffeeShoutEventsTopic);
        return container;
    }
}
