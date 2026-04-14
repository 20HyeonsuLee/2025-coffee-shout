package coffeeshout.racinggame.application;

import coffeeshout.global.exception.custom.InvalidStateException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RacingGameFacade {

    private final RacingGameService racingGameService;

    public void tap(final String joinCode, final String hostName, final int tapCount) {
        try {
            racingGameService.tap(joinCode, hostName, tapCount);
        } catch (InvalidStateException e) {
            // 게임 상태 오류는 로깅 생략 (기존 TapCommandEventHandler 동작 유지)
        } catch (Exception e) {
            // 그 외 예외도 상위 전파 없이 흡수 (기존 동작 유지)
        }
    }
}
