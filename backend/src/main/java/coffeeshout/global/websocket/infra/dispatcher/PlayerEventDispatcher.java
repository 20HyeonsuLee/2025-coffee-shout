package coffeeshout.global.websocket.infra.dispatcher;

import coffeeshout.global.exception.custom.InvalidArgumentException;
import coffeeshout.global.exception.custom.InvalidStateException;
import coffeeshout.global.websocket.event.player.PlayerBaseEvent;
import coffeeshout.global.websocket.infra.handler.PlayerEventHandler;
import coffeeshout.global.websocket.infra.handler.PlayerEventHandlerFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PlayerEventDispatcher {

    private final PlayerEventHandlerFactory handlerFactory;

    @EventListener
    public void onPlayerEvent(final PlayerBaseEvent event) {
        if (!handlerFactory.canHandle(event.eventType())) {
            log.warn("처리할 수 없는 플레이어 이벤트 타입: {}", event.eventType());
            return;
        }

        try {
            final PlayerEventHandler<PlayerBaseEvent> handler = handlerFactory.getHandler(event.eventType());
            handler.handle(event);
        } catch (InvalidStateException | InvalidArgumentException e) {
            log.warn("플레이어 이벤트 처리 중 오류: eventType={}", event.eventType(), e);
        } catch (Exception e) {
            log.error("플레이어 이벤트 처리 실패: eventType={}", event.eventType(), e);
        }
    }
}
