package coffeeshout.test.config;


import coffeeshout.global.websocket.interceptor.ShutdownAwareHandshakeInterceptor;
import coffeeshout.test.config.interceptor.WebSocketInboundMetricInterceptor;
import coffeeshout.test.config.interceptor.WebSocketOutboundMetricInterceptor;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
@Getter
public class WebSocketMessageBrokerConfig implements WebSocketMessageBrokerConfigurer {

    private final WebSocketInboundMetricInterceptor webSocketInboundMetricInterceptor;
    private final WebSocketOutboundMetricInterceptor webSocketOutboundMetricInterceptor;
    private final ShutdownAwareHandshakeInterceptor shutdownAwareHandshakeInterceptor;

    @Value("${websocket.inbound.virtual-threads:false}")
    private boolean useVirtualThreads;

    private TaskExecutor inboundExecutor;
    private ThreadPoolTaskExecutor outboundExecutor;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        ThreadPoolTaskScheduler heartbeatScheduler = new ThreadPoolTaskScheduler();
        heartbeatScheduler.setPoolSize(1);
        heartbeatScheduler.setThreadNamePrefix("wss-heartbeat-thread-");
        heartbeatScheduler.initialize();

        config.enableSimpleBroker("/topic/", "/queue/")
                .setHeartbeatValue(new long[]{4000, 4000})
                .setTaskScheduler(heartbeatScheduler);

        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .addInterceptors(shutdownAwareHandshakeInterceptor)
                .withSockJS();
    }

    @Bean
    public TaskExecutor clientInboundChannelExecutor() {
        if (useVirtualThreads) {
            final SimpleAsyncTaskExecutor virtualExecutor = new SimpleAsyncTaskExecutor("inbound-");
            virtualExecutor.setVirtualThreads(true);
            inboundExecutor = virtualExecutor;
            return virtualExecutor;
        }

        final ThreadPoolTaskExecutor poolExecutor = new ThreadPoolTaskExecutor();
        poolExecutor.setCorePoolSize(8);
        poolExecutor.setMaxPoolSize(8);
        poolExecutor.setQueueCapacity(2048);
        poolExecutor.setThreadNamePrefix("inbound-");
        poolExecutor.initialize();
        inboundExecutor = poolExecutor;
        return poolExecutor;
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(webSocketInboundMetricInterceptor);
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        outboundExecutor = new ThreadPoolTaskExecutor();
        outboundExecutor.setThreadNamePrefix("outbound-");
        outboundExecutor.setCorePoolSize(16);
        outboundExecutor.setMaxPoolSize(16);
        outboundExecutor.setQueueCapacity(2048);
        outboundExecutor.setKeepAliveSeconds(60);
        outboundExecutor.initialize();
        registration.interceptors(webSocketOutboundMetricInterceptor).executor(outboundExecutor);
    }
}
