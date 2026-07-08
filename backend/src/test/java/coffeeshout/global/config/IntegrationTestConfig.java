package coffeeshout.global.config;

import coffeeshout.global.scheduler.DelayedTaskScheduler;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.TaskScheduler;

@TestConfiguration
@Profile("test")
public class IntegrationTestConfig {

    @Bean(name = "cardGameTaskScheduler")
    public TaskScheduler testIntegrationCardGameTaskScheduler() {
        return new ShutDownTestScheduler();
    }

    @Bean
    public DelayedTaskScheduler testIntegrationDelayedTaskScheduler() {
        return new FakeDelayedTaskScheduler();
    }

    @Bean(name = "racingGameScheduler")
    public TaskScheduler testIntegrationRacingGameScheduler() {
        return new ShutDownTestScheduler();
    }
}
