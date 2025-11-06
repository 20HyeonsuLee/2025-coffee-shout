package coffeeshout.test.config.interceptor;

import coffeeshout.test.metric.WebSocketMetricService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketInboundMetricInterceptor implements ExecutorChannelInterceptor {

    private final WebSocketMetricService webSocketMetricService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        final StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (!isMetricsCollectible(accessor)) {
            return message;
        }

        final String messageId = UUID.randomUUID().toString();
        accessor.setHeader("messageId", messageId);

        webSocketMetricService.startInboundMessageTimer(messageId);
        webSocketMetricService.incrementInboundMessage();

        return message;
    }

    @Override
    public Message<?> beforeHandle(Message<?> message, MessageChannel channel, MessageHandler handler) {
        final StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (!isMetricsCollectible(accessor)) {
            return message;
        }

        final String messageId = (String) accessor.getHeader("messageId");

        webSocketMetricService.startBusinessTimer(messageId);

        return message;
    }

    @Override
    public void afterMessageHandled(Message<?> message, MessageChannel channel, MessageHandler handler, Exception ex) {
        final StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (!isMetricsCollectible(accessor)) {
            return;
        }

        String messageId = (String) accessor.getHeader("messageId");
        webSocketMetricService.stopInboundMessageTimer(messageId);
        webSocketMetricService.stopBusinessTimer(messageId);
    }

    private boolean isMetricsCollectible(StompHeaderAccessor accessor) {

        if (accessor == null) {
            return false;
        }

        final StompCommand command = accessor.getCommand();

        return command == StompCommand.SEND;
    }
}
