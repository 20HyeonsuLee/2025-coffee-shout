package coffeeshout.room.infra.redis;

import coffeeshout.global.luacommand.core.CompositeLuaBuilder;
import coffeeshout.global.luacommand.core.DecodedReturnCode;
import coffeeshout.global.luacommand.core.LuaCommandMetricListener;
import coffeeshout.global.luacommand.core.QueuedCommand;
import coffeeshout.global.metric.LuaScriptMetricService;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * {@link LuaCommandMetricListener}를 기존 {@link LuaScriptMetricService}로 연결하는 어댑터.
 *
 * <p>composite 안 주요 mutation 명령의 이름을 레거시 스크립트 라벨로 매핑해
 * Grafana 대시보드의 scriptName 축과 호환성을 유지한다. 미매핑 시 {@code "composite"} 라벨.
 */
@Component
public class LuaScriptMetricAdapter implements LuaCommandMetricListener {

    private static final long UNKNOWN_RESULT = -99L;
    private static final String FALLBACK_LABEL = "composite";

    private static final Map<String, String> COMMAND_TO_SCRIPT = Map.of(
            "addPlayerAndPublish", "enter_room",
            "writeReadyAndPublish", "toggle_ready",
            "writeRoomMetaAndPublish", "create_room",
            "writePositionsAndPublish", "update_positions",
            "removePlayerCore", "remove_player"
    );

    private final LuaScriptMetricService luaMetric;

    public LuaScriptMetricAdapter(final LuaScriptMetricService luaMetric) {
        this.luaMetric = luaMetric;
    }

    @Override
    public void onExecution(final List<QueuedCommand> commands, final Long returnCode, final long durationNanos) {
        final String label = resolveLabel(commands);
        final long code = decodeCode(returnCode);
        luaMetric.recordExecution(label, code, durationNanos);
    }

    private long decodeCode(final Long returnCode) {
        if (returnCode == null) {
            return UNKNOWN_RESULT;
        }
        if (returnCode >= 0) {
            return returnCode;
        }
        final DecodedReturnCode decoded = CompositeLuaBuilder.decodeReturnCode(returnCode);
        return decoded.originalCode();
    }

    private String resolveLabel(final List<QueuedCommand> commands) {
        return commands.stream()
                .map(QueuedCommand::name)
                .map(COMMAND_TO_SCRIPT::get)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(FALLBACK_LABEL);
    }
}
