package coffeeshout.racinggame.application;

import coffeeshout.racinggame.domain.event.TapCommandEvent;
import coffeeshout.racinggame.infra.messaging.RacingGameStreamProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RacingGameFacade {

    private final RacingGameStreamProducer racingGameStreamProducer;

    public void tap(String joinCode, String hostName, int tapCount) {
        final TapCommandEvent event = TapCommandEvent.create(joinCode, hostName, tapCount);
        racingGameStreamProducer.publishEvent(event);
    }
}
