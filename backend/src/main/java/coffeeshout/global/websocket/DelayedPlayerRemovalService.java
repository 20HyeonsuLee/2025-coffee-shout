package coffeeshout.global.websocket;

import coffeeshout.global.scheduler.DelayedTask;
import coffeeshout.global.scheduler.DelayedTaskHandler;
import coffeeshout.global.scheduler.DelayedTaskScheduler;
import coffeeshout.global.scheduler.DelayedTaskType;
import coffeeshout.room.application.RoomService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 연결이 끊긴 플레이어를 grace period 후 방에서 제거한다.
 *
 * <p>타이머는 WAS 메모리가 아니라 Redis에 있다({@link DelayedTaskScheduler}).
 * 따라서 스케줄을 등록한 WAS가 죽어도 다른 WAS의 폴러가 제거를 실행하고,
 * 플레이어가 다른 WAS로 재접속해도 취소가 동작한다.
 */
@Slf4j
@Service
public class DelayedPlayerRemovalService implements DelayedTaskHandler {

    private static final Duration REMOVAL_DELAY = Duration.ofSeconds(15);

    private final DelayedTaskScheduler taskScheduler;
    private final PlayerDisconnectionService playerDisconnectionService;
    private final RoomService roomService;
    private final StompSessionManager stompSessionManager;
    private final ObjectMapper objectMapper;

    public DelayedPlayerRemovalService(
            final DelayedTaskScheduler taskScheduler,
            final PlayerDisconnectionService playerDisconnectionService,
            final StompSessionManager stompSessionManager,
            final RoomService roomService,
            final ObjectMapper objectMapper
    ) {
        this.taskScheduler = taskScheduler;
        this.playerDisconnectionService = playerDisconnectionService;
        this.roomService = roomService;
        this.stompSessionManager = stompSessionManager;
        this.objectMapper = objectMapper;
    }

    public void schedulePlayerRemoval(final String playerKey, final String sessionId, final String reason) {
        final String joinCode = playerKey.split(":")[0];
        if (!roomService.isReadyState(joinCode)) {
            return;
        }

        log.info("플레이어 지연 삭제 스케줄링: playerKey={}, sessionId={}, delay={}초",
                playerKey, sessionId, REMOVAL_DELAY.getSeconds());

        // disconnect 된 플레이어는 ready 상태 false로 변경
        playerDisconnectionService.cancelReady(playerKey);

        final String payload = serializePayload(new PlayerRemovalPayload(playerKey, sessionId, reason));
        taskScheduler.schedule(
                new DelayedTask(DelayedTaskType.PLAYER_REMOVAL, playerKey, payload),
                Instant.now().plus(REMOVAL_DELAY)
        );
    }

    public void cancelScheduledRemoval(final String playerKey) {
        taskScheduler.cancel(DelayedTaskType.PLAYER_REMOVAL, playerKey);
        log.info("플레이어 지연 삭제 취소 요청: playerKey={}", playerKey);
    }

    @Override
    public DelayedTaskType supportedType() {
        return DelayedTaskType.PLAYER_REMOVAL;
    }

    @Override
    public void handle(final String taskId, final String payload) {
        final PlayerRemovalPayload removal = deserializePayload(taskId, payload);
        executePlayerRemoval(removal.playerKey(), removal.sessionId(), removal.reason());
        stompSessionManager.removeSessionInternal(removal.sessionId());
    }

    private void executePlayerRemoval(final String playerKey, final String sessionId, final String reason) {
        try {
            log.info("플레이어 지연 삭제 실행: playerKey={}, sessionId={}, reason={}",
                    playerKey, sessionId, reason);

            playerDisconnectionService.handlePlayerDisconnection(playerKey, sessionId, reason);

        } catch (Exception e) {
            log.error("플레이어 지연 삭제 실행 중 오류 발생: playerKey={}, error={}",
                    playerKey, e.getMessage(), e);
        }
    }

    private String serializePayload(final PlayerRemovalPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("플레이어 삭제 payload 직렬화 실패: " + payload.playerKey(), e);
        }
    }

    private PlayerRemovalPayload deserializePayload(final String taskId, final String payload) {
        try {
            return objectMapper.readValue(payload, PlayerRemovalPayload.class);
        } catch (JsonProcessingException e) {
            // 등록 직후 취소와 경합해 payload가 비어 있으면 taskId(playerKey)만으로 제거를 진행한다
            log.warn("플레이어 삭제 payload 역직렬화 실패, taskId로 대체: taskId={}, payload={}", taskId, payload);
            return new PlayerRemovalPayload(taskId, "", "PAYLOAD_MISSING");
        }
    }

    public record PlayerRemovalPayload(String playerKey, String sessionId, String reason) {
    }
}
