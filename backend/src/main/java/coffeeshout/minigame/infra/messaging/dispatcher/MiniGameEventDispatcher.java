package coffeeshout.minigame.infra.messaging.dispatcher;

import coffeeshout.global.trace.TracerProvider;
import coffeeshout.minigame.event.MiniGameBaseEvent;
import coffeeshout.minigame.event.MiniGameEventType;
import coffeeshout.minigame.infra.messaging.handler.MiniGameEventHandler;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class MiniGameEventDispatcher {

    private final Map<MiniGameEventType, MiniGameEventHandler<MiniGameBaseEvent>> handlers;
    private final TracerProvider tracerProvider;

    @SuppressWarnings("unchecked")
    public MiniGameEventDispatcher(
            final List<MiniGameEventHandler<?>> handlers,
            final TracerProvider tracerProvider
    ) {
        this.handlers = handlers.stream()
                .collect(Collectors.toMap(
                        MiniGameEventHandler::getSupportedEventType,
                        handler -> (MiniGameEventHandler<MiniGameBaseEvent>) handler
                ));
        this.tracerProvider = tracerProvider;
    }

    @EventListener
    public void onMiniGameEvent(final MiniGameBaseEvent event) {
        if (!handlers.containsKey(event.eventType())) {
            log.warn("처리할 수 없는 미니게임 이벤트 타입: {}", event.eventType());
            return;
        }

        final MiniGameEventHandler<MiniGameBaseEvent> handler = handlers.get(event.eventType());
        tracerProvider.executeWithTraceContext(
                event.traceInfo(),
                () -> handler.handle(event),
                event.eventType().name()
        );
    }
}
