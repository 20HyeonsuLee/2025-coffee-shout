package coffeeshout.test.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class InboundChannelExecutorConfig {

    @Value("${websocket.inbound.virtual-threads:false}")
    private boolean useVirtualThreads;

    @Bean
    public static BeanPostProcessor inboundChannelExecutorPostProcessor() {
        return new InboundChannelExecutorPostProcessor();
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
                return virtualExecutor;
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
