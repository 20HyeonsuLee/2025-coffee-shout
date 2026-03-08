package coffeeshout.test.config;

import coffeeshout.global.config.properties.RedisProperties;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.metrics.MicrometerCommandLatencyRecorder;
import io.lettuce.core.metrics.MicrometerOptions;
import io.lettuce.core.resource.ClientResources;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IsolatedRedisClientConfig {

    @Bean(destroyMethod = "shutdown")
    public ClientResources loadtestClientResources(
            final MeterRegistry meterRegistry,
            @Value("${loadtest.xadd.io-threads:4}") final int ioThreads
    ) {
        final MicrometerOptions options = MicrometerOptions.builder()
                .enable()
                .histogram(true)
                .build();

        return ClientResources.builder()
                .ioThreadPoolSize(ioThreads)
                .computationThreadPoolSize(ioThreads)
                .commandLatencyRecorder(new MicrometerCommandLatencyRecorder(meterRegistry, options))
                .build();
    }

    @Bean(destroyMethod = "shutdown")
    public RedisClient loadtestRedisClient(
            @Qualifier("loadtestClientResources") final ClientResources loadtestClientResources,
            final RedisProperties redisProperties
    ) {
        final RedisURI.Builder uriBuilder = RedisURI.builder()
                .withHost(redisProperties.host())
                .withPort(redisProperties.port());

        if (redisProperties.ssl().enabled()) {
            uriBuilder.withSsl(true);
        }

        return RedisClient.create(loadtestClientResources, uriBuilder.build());
    }
}
