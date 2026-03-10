package coffeeshout.racinggame.application;

import coffeeshout.racinggame.infra.messaging.RacingGameStreamProducer;
import coffeeshout.test.infrastructure.IsolatedXaddProducer;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RacingGameFacade {

    private final RacingGameStreamProducer racingGameStreamProducer;
    private final IsolatedXaddProducer isolatedXaddProducer;
    private final AtomicLong sequence = new AtomicLong(0);

    public void tap(String joinCode, String hostName, int tapCount) {
        isolatedXaddProducer.xaddAsync(sequence.getAndIncrement());
    }
}
