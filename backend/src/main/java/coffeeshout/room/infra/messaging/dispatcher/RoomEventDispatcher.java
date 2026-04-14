package coffeeshout.room.infra.messaging.dispatcher;

import coffeeshout.global.trace.Traceable;
import coffeeshout.global.trace.TracerProvider;
import coffeeshout.room.domain.event.RoomBaseEvent;
import coffeeshout.room.infra.messaging.handler.RoomEventHandler;
import coffeeshout.room.infra.messaging.handler.RoomEventHandlerFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RoomEventDispatcher {

    private final RoomEventHandlerFactory handlerFactory;
    private final TracerProvider tracerProvider;

    @EventListener
    public void onRoomEvent(final RoomBaseEvent event) {
        if (!handlerFactory.canHandle(event.eventType())) {
            log.warn("처리할 수 없는 이벤트 타입: {}", event.eventType());
            return;
        }

        final RoomEventHandler<RoomBaseEvent> handler = handlerFactory.getHandler(event.eventType());
        if (event instanceof Traceable traceable) {
            tracerProvider.executeWithTraceContext(
                    traceable.getTraceInfo(),
                    () -> handler.handle(event),
                    event.eventType().name()
            );
            return;
        }
        handler.handle(event);
    }
}
