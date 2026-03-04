package coffeeshout.global.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.apache.coyote.ProtocolHandler;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TomcatMetricsConfig {

    @Bean
    public MeterBinder tomcatThreadPoolMetrics(final ServletWebServerApplicationContext context) {
        return registry -> {
            final TomcatWebServer webServer = (TomcatWebServer) context.getWebServer();
            final ProtocolHandler handler = webServer.getTomcat()
                    .getConnector()
                    .getProtocolHandler();
            final Executor executor = handler.getExecutor();

            if (executor instanceof ThreadPoolExecutor threadPool) {
                registerThreadPoolMetrics(registry, threadPool);
            }
        };
    }

    private void registerThreadPoolMetrics(final MeterRegistry registry,
                                           final ThreadPoolExecutor threadPool) {
        Gauge.builder("tomcat.queue.size", threadPool, ThreadPoolExecutor::getPoolSize)
                .description("Tomcat 스레드 풀 현재 스레드 수")
                .register(registry);

        Gauge.builder("tomcat.queue.pending", threadPool.getQueue()::size)
                .description("Tomcat 작업 큐 대기 중인 요청 수")
                .register(registry);

        Gauge.builder("tomcat.queue.remaining_capacity", threadPool.getQueue()::remainingCapacity)
                .description("Tomcat 작업 큐 남은 용량")
                .register(registry);
    }
}
