package coffeeshout.test.load;

import coffeeshout.fixture.TestStompSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

public class LoadTestClient {

    private static final Logger log = LoggerFactory.getLogger(LoadTestClient.class);
    private static final String WEBSOCKET_BASE_URL_FORMAT = "ws://localhost:%d/ws";

    private final List<TestStompSession> sessions;
    private final ScheduledExecutorService scheduler;
    private final int connectionCount;

    private ScheduledFuture<?> sendFuture;

    public LoadTestClient(int connectionCount, int port, ObjectMapper objectMapper) throws Exception {
        this.connectionCount = connectionCount;
        this.sessions = new ArrayList<>();
        this.scheduler = Executors.newScheduledThreadPool(connectionCount);

        // 다중 연결 생성
        String url = String.format(WEBSOCKET_BASE_URL_FORMAT, port);
        for (int i = 0; i < connectionCount; i++) {
            TestStompSession session = createSession(url, objectMapper);
            sessions.add(session);
        }
        log.info("LoadTestClient initialized with {} connections", connectionCount);
    }

    private TestStompSession createSession(String url, ObjectMapper objectMapper) throws Exception {
        SockJsClient sockJsClient = new SockJsClient(List.of(
                new WebSocketTransport(new StandardWebSocketClient())
        ));

        WebSocketStompClient stompClient = new WebSocketStompClient(sockJsClient);
        MappingJackson2MessageConverter messageConverter = new MappingJackson2MessageConverter(objectMapper);
        messageConverter.setStrictContentTypeMatch(false);
        stompClient.setMessageConverter(messageConverter);

        StompSession session = stompClient.connectAsync(url, new StompSessionHandlerAdapter() {
            @Override
            public void handleTransportError(StompSession session, Throwable exception) {
                log.error("STOMP TRANSPORT ERROR: " + exception.getMessage());
            }

            @Override
            public void handleException(
                    StompSession session,
                    StompCommand command,
                    StompHeaders headers,
                    byte[] payload,
                    Throwable exception
            ) {
                log.error("STOMP EXCEPTION: " + exception.getMessage());
            }
        }).get(5, TimeUnit.SECONDS);

        return new TestStompSession(session, objectMapper);
    }

    /**
     * 지정된 TPS로 메시지 전송 시작
     * @param endpoint 메시지 전송 엔드포인트
     * @param tps 초당 전송할 메시지 개수
     * @param messageSupplier 메시지 생성 함수
     */
    public void startSending(String endpoint, int tps, Supplier<Object> messageSupplier) {
        if (tps <= 0) {
            throw new IllegalArgumentException("TPS must be positive");
        }

        // 각 연결당 전송할 TPS 계산
        int tpsPerConnection = tps / connectionCount;
        int remainingTps = tps % connectionCount;

        // 각 세션에 대해 메시지 전송 스케줄링
        for (int i = 0; i < sessions.size(); i++) {
            TestStompSession session = sessions.get(i);
            int actualTps = tpsPerConnection;

            // 나머지 TPS를 첫 번째 세션들에 분배
            if (i < remainingTps) {
                actualTps++;
            }

            if (actualTps > 0) {
                long intervalMillis = 1000L / actualTps;
                scheduler.scheduleAtFixedRate(
                        () -> {
                            try {
                                Object message = messageSupplier.get();
                                session.send(endpoint, message);
                            } catch (Exception e) {
                                log.error("Error sending message", e);
                            }
                        },
                        0,
                        intervalMillis,
                        TimeUnit.MILLISECONDS
                );
            }
        }

        log.info("Started sending messages at {} TPS to endpoint {}", tps, endpoint);
    }

    /**
     * 메시지 전송 중지
     */
    public void stopSending() {
        if (sendFuture != null) {
            sendFuture.cancel(false);
        }
        log.info("Stopped sending messages");
    }

    /**
     * 모든 연결 종료
     */
    public void closeAll() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("LoadTestClient closed");
    }
}
