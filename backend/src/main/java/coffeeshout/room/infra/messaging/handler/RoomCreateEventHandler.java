package coffeeshout.room.infra.messaging.handler;

import coffeeshout.room.domain.event.RoomCreateEvent;
import coffeeshout.room.domain.event.RoomEventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RoomCreateEventHandler implements RoomEventHandler<RoomCreateEvent> {

    @Override
    public void handle(RoomCreateEvent event) {
        log.info("방 생성 이벤트 수신: eventId={}, hostName={}, joinCode={}",
                event.eventId(), event.hostName(), event.joinCode());
    }

    @Override
    public RoomEventType getSupportedEventType() {
        return RoomEventType.ROOM_CREATE;
    }
}
