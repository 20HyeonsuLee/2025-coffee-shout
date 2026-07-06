package coffeeshout.global.scheduler;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

import coffeeshout.global.metric.SchedulerMetricService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DelayedTaskPollerTest {

    @Mock
    private RedisDelayedTaskScheduler taskScheduler;

    @Mock
    private SchedulerMetricService schedulerMetric;

    @Mock
    private DelayedTaskHandler playerRemovalHandler;

    private DelayedTaskPoller poller;

    private final Instant now = Instant.now();

    @BeforeEach
    void setUp() {
        given(playerRemovalHandler.supportedType()).willReturn(DelayedTaskType.PLAYER_REMOVAL);
        poller = new DelayedTaskPoller(taskScheduler, schedulerMetric, List.of(playerRemovalHandler), 100);
    }

    @Test
    void 소비된_작업을_타입에_맞는_핸들러로_분배한다() {
        // given
        given(taskScheduler.consumeDue(any(Instant.class), anyInt())).willReturn(List.of(
                new ConsumedDelayedTask("PLAYER_REMOVAL:ABC23:김철수", now.toEpochMilli(), "payload")
        ));

        // when
        poller.pollOnce(now);

        // then
        then(playerRemovalHandler).should().handle("ABC23:김철수", "payload");
        then(schedulerMetric).should().recordConsumed(any(DelayedTaskType.class), anyLong());
    }

    @Test
    void 핸들러가_실패해도_나머지_작업은_계속_처리한다() {
        // given
        given(taskScheduler.consumeDue(any(Instant.class), anyInt())).willReturn(List.of(
                new ConsumedDelayedTask("PLAYER_REMOVAL:ROOM1:실패플레이어", now.toEpochMilli(), "p1"),
                new ConsumedDelayedTask("PLAYER_REMOVAL:ROOM2:정상플레이어", now.toEpochMilli(), "p2")
        ));
        willThrow(new RuntimeException("삭제 실패"))
                .given(playerRemovalHandler).handle("ROOM1:실패플레이어", "p1");

        // when & then
        assertThatCode(() -> poller.pollOnce(now)).doesNotThrowAnyException();
        then(playerRemovalHandler).should().handle("ROOM2:정상플레이어", "p2");
        then(schedulerMetric).should().recordFailed(DelayedTaskType.PLAYER_REMOVAL);
    }

    @Test
    void 알_수_없는_타입의_작업은_폐기하고_계속_진행한다() {
        // given
        given(taskScheduler.consumeDue(any(Instant.class), anyInt())).willReturn(List.of(
                new ConsumedDelayedTask("UNKNOWN_TYPE:xxx", now.toEpochMilli(), "p1"),
                new ConsumedDelayedTask("PLAYER_REMOVAL:ROOM2:정상플레이어", now.toEpochMilli(), "p2")
        ));

        // when & then
        assertThatCode(() -> poller.pollOnce(now)).doesNotThrowAnyException();
        then(playerRemovalHandler).should().handle("ROOM2:정상플레이어", "p2");
    }
}
