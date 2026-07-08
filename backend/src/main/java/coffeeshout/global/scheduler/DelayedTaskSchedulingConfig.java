package coffeeshout.global.scheduler;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@link DelayedTaskPoller}의 {@code @Scheduled} 폴링 루프 활성화.
 */
@Configuration
@EnableScheduling
public class DelayedTaskSchedulingConfig {
}
