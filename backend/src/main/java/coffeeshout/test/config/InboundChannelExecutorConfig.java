package coffeeshout.test.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class InboundChannelExecutorConfig {

    @Bean
    public static BeanPostProcessor inboundChannelExecutorPostProcessor() {
        return new InboundChannelExecutorPostProcessor();
    }

    @Bean
    public MeterBinder inboundExecutorMetrics(
            @Qualifier("clientInboundChannelExecutor") TaskExecutor executor) {
        return registry -> {
            if (executor instanceof CountingTaskExecutor counting) {
                Gauge.builder("websocket.inbound.vt.active", counting::getActiveCount)
                        .description("현재 실행 중인 inbound 가상 스레드 수")
                        .register(registry);
                Gauge.builder("websocket.inbound.vt.total", counting::getTotalCount)
                        .description("생성된 inbound 가상 스레드 총 수")
                        .register(registry);
            }
        };
    }

    private static class InboundChannelExecutorPostProcessor implements BeanPostProcessor {

        @Value("${websocket.inbound.virtual-threads:false}")
        private boolean useVirtualThreads;

        @Value("${websocket.inbound.concurrency-limit:64}")
        private int concurrencyLimit;

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (!"clientInboundChannelExecutor".equals(beanName)) {
                return bean;
            }

            if (useVirtualThreads) {
                final SimpleAsyncTaskExecutor virtualExecutor = new SimpleAsyncTaskExecutor("inbound-");
                virtualExecutor.setVirtualThreads(true);
                virtualExecutor.setConcurrencyLimit(concurrencyLimit);
                return new CountingTaskExecutor(virtualExecutor);
            }

            final ThreadPoolTaskExecutor poolExecutor = new ThreadPoolTaskExecutor();
            poolExecutor.setCorePoolSize(32);
            poolExecutor.setMaxPoolSize(32);
            poolExecutor.setQueueCapacity(2048);
            poolExecutor.setThreadNamePrefix("inbound-");
            poolExecutor.initialize();
            return poolExecutor;
        }
    }
}
