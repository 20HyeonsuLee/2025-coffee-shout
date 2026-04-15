package coffeeshout.global.messaging;

import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * WAS 기동 시 단일 인스턴스 식별자를 생성해 빈으로 등록.
 * Pub/Sub 자기 메시지 필터에서 사용한다.
 */
@Configuration
@org.springframework.context.annotation.Profile("!test")
public class InstanceIdConfig {

    @Bean(name = "selfInstanceId")
    public String selfInstanceId() {
        return UUID.randomUUID().toString();
    }
}
