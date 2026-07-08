package coffeeshout.global;

import coffeeshout.global.config.ServiceTestConfig;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import(ServiceTestConfig.class)
@ActiveProfiles("test")
@Transactional
public abstract class ServiceTest {
}
