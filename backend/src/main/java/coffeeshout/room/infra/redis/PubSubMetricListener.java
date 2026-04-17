package coffeeshout.room.infra.redis;

import coffeeshout.global.luacommand.core.LuaCommandMetricListener;
import coffeeshout.global.luacommand.core.QueuedCommand;
import coffeeshout.global.metric.PubSubMetricService;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * composite Lua 성공 시, 내부 PUBLISH 명령에 해당하는 이벤트 타입을
 * {@link PubSubMetricService}로 카운트한다.
 *
 * <p>기존 inline {@code pubSubMetric.recordPublish(eventType)} 호출을 대체한다.
 * Lua 성공(return 1) 일 때만 기록하므로 원자 실행과 메트릭이 일관.
 */
@Component
public class PubSubMetricListener implements LuaCommandMetricListener {

    private static final long SUCCESS = 1L;

    private static final Map<String, String> COMMAND_TO_EVENT = Map.of(
            "addPlayerAndPublish", "PLAYER_LIST_UPDATE",
            "writeReadyAndPublish", "PLAYER_READY",
            "writeRoomMetaAndPublish", "ROOM_CREATE",
            "writePositionsAndPublish", "RACING_POSITIONS",
            "removePlayerCore", "PLAYER_LIST_UPDATE"
    );

    private final PubSubMetricService pubSubMetric;

    public PubSubMetricListener(final PubSubMetricService pubSubMetric) {
        this.pubSubMetric = pubSubMetric;
    }

    @Override
    public void onExecution(final List<QueuedCommand> commands, final Long returnCode, final long durationNanos) {
        if (returnCode == null || returnCode != SUCCESS) {
            return;
        }
        commands.stream()
                .map(QueuedCommand::name)
                .map(COMMAND_TO_EVENT::get)
                .filter(eventType -> eventType != null)
                .forEach(pubSubMetric::recordPublish);
    }
}
