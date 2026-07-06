package coffeeshout.global.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import coffeeshout.global.scheduler.DelayedTask;
import coffeeshout.global.scheduler.DelayedTaskScheduler;
import coffeeshout.global.scheduler.DelayedTaskType;
import coffeeshout.global.websocket.DelayedPlayerRemovalService.PlayerRemovalPayload;
import coffeeshout.room.application.RoomService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DelayedPlayerRemovalServiceTest {

    @Mock
    private DelayedTaskScheduler taskScheduler;

    @Mock
    private PlayerDisconnectionService playerDisconnectionService;

    @Mock
    private RoomService roomService;

    @Mock
    private StompSessionManager sessionManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private DelayedPlayerRemovalService delayedPlayerRemovalService;

    private final String playerKey = "ABC23:김철수";
    private final String sessionId = "session-123";
    private final String reason = "CLIENT_DISCONNECT";

    @BeforeEach
    void setUp() {
        delayedPlayerRemovalService = new DelayedPlayerRemovalService(taskScheduler, playerDisconnectionService,
                sessionManager, roomService, objectMapper);
    }

    @Nested
    class 플레이어_지연_삭제_스케줄링 {

        @Test
        void 정상적으로_지연_삭제를_스케줄링한다() {
            // given
            given(roomService.isReadyState("ABC23")).willReturn(true);

            // when
            delayedPlayerRemovalService.schedulePlayerRemoval(playerKey, sessionId, reason);

            // then
            final ArgumentCaptor<DelayedTask> taskCaptor = ArgumentCaptor.forClass(DelayedTask.class);
            then(taskScheduler).should().schedule(taskCaptor.capture(), any(Instant.class));
            final DelayedTask task = taskCaptor.getValue();
            assertThat(task.type()).isEqualTo(DelayedTaskType.PLAYER_REMOVAL);
            assertThat(task.id()).isEqualTo(playerKey);
        }

        @Test
        void 게임중이면_지연_삭제를_스케줄링_안한다() {
            // given
            given(roomService.isReadyState("ABC23")).willReturn(false);

            // when
            delayedPlayerRemovalService.schedulePlayerRemoval(playerKey, sessionId, reason);

            // then
            then(taskScheduler).should(never()).schedule(any(DelayedTask.class), any(Instant.class));
            then(playerDisconnectionService).should(never()).cancelReady(any());
        }

        @Test
        void 서로_다른_플레이어는_독립적으로_스케줄링된다() {
            // given
            String anotherPlayerKey = "DEF456:박영희";
            given(roomService.isReadyState("ABC23")).willReturn(true);
            given(roomService.isReadyState("DEF456")).willReturn(true);

            // when
            delayedPlayerRemovalService.schedulePlayerRemoval(playerKey, sessionId, reason);
            delayedPlayerRemovalService.schedulePlayerRemoval(anotherPlayerKey, "session-456", reason);

            // then
            then(taskScheduler).should(times(2)).schedule(any(DelayedTask.class), any(Instant.class));
        }
    }

    @Nested
    class 지연_삭제_취소 {

        @Test
        void 스케줄된_삭제를_스케줄러에_취소_위임한다() {
            // when
            delayedPlayerRemovalService.cancelScheduledRemoval(playerKey);

            // then
            then(taskScheduler).should().cancel(DelayedTaskType.PLAYER_REMOVAL, playerKey);
        }

        @Test
        void 존재하지_않는_플레이어의_취소_요청도_위임한다() {
            // 취소는 멱등 연산이므로 존재 여부와 무관하게 위임한다 (다른 WAS가 등록한 스케줄일 수 있음)
            assertThatCode(() -> delayedPlayerRemovalService.cancelScheduledRemoval("없는방:없는플레이어"))
                    .doesNotThrowAnyException();
            then(taskScheduler).should().cancel(DelayedTaskType.PLAYER_REMOVAL, "없는방:없는플레이어");
        }
    }

    @Nested
    class 지연_삭제_실행 {

        @Test
        void payload를_역직렬화해_PlayerDisconnectionService를_호출한다() throws Exception {
            // given
            final String payload = objectMapper.writeValueAsString(
                    new PlayerRemovalPayload(playerKey, sessionId, reason));

            // when
            delayedPlayerRemovalService.handle(playerKey, payload);

            // then
            then(playerDisconnectionService).should()
                    .handlePlayerDisconnection(playerKey, sessionId, reason);
            then(sessionManager).should().removeSessionInternal(sessionId);
        }

        @Test
        void PlayerDisconnectionService에서_예외_발생해도_안전하게_처리한다() throws Exception {
            // given
            willThrow(new RuntimeException("플레이어 삭제 실패"))
                    .given(playerDisconnectionService)
                    .handlePlayerDisconnection(any(), any(), any());
            final String payload = objectMapper.writeValueAsString(
                    new PlayerRemovalPayload(playerKey, sessionId, reason));

            // when & then - 예외가 터져도 프로그램이 죽지 않음
            assertThatCode(() -> delayedPlayerRemovalService.handle(playerKey, payload))
                    .doesNotThrowAnyException();
        }

        @Test
        void payload가_손상되면_taskId를_playerKey로_사용해_제거를_진행한다() {
            // when
            delayedPlayerRemovalService.handle(playerKey, "");

            // then
            then(playerDisconnectionService).should()
                    .handlePlayerDisconnection(eq(playerKey), any(), any());
        }
    }
}
