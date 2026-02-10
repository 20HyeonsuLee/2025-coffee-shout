package coffeeshout.test.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import coffeeshout.global.config.IntegrationTestConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(IntegrationTestConfig.class)
@TestPropertySource(properties = "websocket.inbound.virtual-threads=true")
class InboundChannelExecutorConfigTest {

    @LocalServerPort
    private int port;

    @Autowired
    @Qualifier("clientInboundChannel")
    private ExecutorSubscribableChannel clientInboundChannel;

    @Test
    @DisplayName("STOMP 메시지가 실제로 가상 스레드에서 처리된다")
    void inboundMessageProcessedByVirtualThread() throws Exception {
        // given - executor 스레드에서 실행되는 beforeHandle로 캡처
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Thread> capturedThread = new AtomicReference<>();

        clientInboundChannel.addInterceptor(new ExecutorChannelInterceptor() {
            @Override
            public Message<?> beforeHandle(Message<?> message, MessageChannel channel, MessageHandler handler) {
                final Thread current = Thread.currentThread();
                if (capturedThread.compareAndSet(null, current)) {
                    latch.countDown();
                }
                return message;
            }
        });

        // when - 실제 WebSocket 연결 후 STOMP 메시지 전송
        final SockJsClient sockJsClient = new SockJsClient(
                List.of(new WebSocketTransport(new StandardWebSocketClient()))
        );
        final WebSocketStompClient stompClient = new WebSocketStompClient(sockJsClient);
        final String url = "ws://localhost:" + port + "/ws";

        final StompSession session = stompClient.connectAsync(url, new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);

        session.send("/app/test/load/request", "{\"message\":\"ping\"}".getBytes());

        // then
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        final Thread thread = capturedThread.get();
        System.out.println("=== Inbound 메시지 처리 스레드 검증 ===");
        System.out.println("Thread name: " + thread.getName());
        System.out.println("Thread isVirtual: " + thread.isVirtual());

        assertThat(thread.isVirtual())
                .as("inbound 메시지가 가상 스레드에서 처리되어야 한다")
                .isTrue();
        assertThat(thread.getName()).startsWith("inbound-");

        session.disconnect();
    }
}
