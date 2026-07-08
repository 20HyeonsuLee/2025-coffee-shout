package coffeeshout.room.infra.messaging.handler;

import coffeeshout.room.application.RouletteService;
import coffeeshout.room.domain.event.RouletteShowEvent;
import coffeeshout.room.domain.event.RouletteSpinEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RoulettePersistenceService {

    private final RouletteService rouletteService;

    public void saveRoomStatus(RouletteShowEvent event) {
        rouletteService.updateRoomStatusToRoulette(event.joinCode());
        log.info("룰렛 상태 DB 저장 완료: eventId={}, joinCode={}", event.eventId(), event.joinCode());
    }

    public void saveRouletteResult(RouletteSpinEvent event) {
        rouletteService.saveRouletteResult(event.joinCode(), event.winner());
        log.info("룰렛 결과 DB 저장 완료: eventId={}, joinCode={}, winner={}",
                event.eventId(), event.joinCode(), event.winner().name().value());
    }
}
