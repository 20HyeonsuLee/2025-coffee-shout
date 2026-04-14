package coffeeshout.global.websocket.infra.dispatcher;

import coffeeshout.global.websocket.event.session.SessionBaseEvent;
import coffeeshout.global.websocket.infra.handler.SessionEventHandler;
import coffeeshout.global.websocket.infra.handler.SessionEventHandlerFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SessionEventDispatcher {

    private final SessionEventHandlerFactory handlerFactory;

    @EventListener
    public void onSessionEvent(final SessionBaseEvent event) {
        if (!handlerFactory.canHandle(event.eventType())) {
            log.warn("처리할 수 없는 세션 이벤트 타입: {}", event.eventType());
            return;
        }

        final SessionEventHandler<SessionBaseEvent> handler = handlerFactory.getHandler(event.eventType());
        handler.handle(event);
    }
}
