package coffeeshout;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.redisson.spring.starter.RedissonAutoConfigurationV2;

@ConfigurationPropertiesScan
@SpringBootApplication(exclude = {
        RedisAutoConfiguration.class,
        RedisReactiveAutoConfiguration.class,
        RedisRepositoriesAutoConfiguration.class,
        RedissonAutoConfigurationV2.class
})
public class CoffeeShoutApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoffeeShoutApplication.class, args);
    }

}

