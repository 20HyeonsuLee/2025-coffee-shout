package coffeeshout.test.load;

import static org.assertj.core.api.Assertions.*;

import coffeeshout.fixture.TestStompSession;
import coffeeshout.test.application.LoadType;
import coffeeshout.test.application.dto.LoadConfigRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

class LoadTest {

    static final int CONNECT_TIMEOUT_SECONDS = 1;
    static final String WEBSOCKET_BASE_URL_FORMAT = "ws://localhost:%d/ws";
    public static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private void send(TestStompSession session) {
        session.send("app/test/load/request", String.format("""
                {
                  "id": 1,
                  "timestamp": "2025-01-06T12:34:56.789Z"
                }
                """));
    }

    private void sessionInit(TestStompSession stompSession) {
        stompSession.subscribe("/topic/test/load/response");
    }

    @Test
    void test1() {
        // given


        // when

        // then
    }

    @Test
    void 부하_테스트_설정() {
        // Given
        final RestTemplate restTemplate = new RestTemplate();
        final String url = "http://localhost:8080/test/load/config";

        final LoadConfigRequest request = new LoadConfigRequest(
                100,
                LoadType.CPU_BOUND,
                60000
        );

        // When
        final ResponseEntity<Void> response = restTemplate.postForEntity(url, request, Void.class);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private void start() {
        final RestTemplate restTemplate = new RestTemplate();
        final String baseUrl = "http://localhost:8080";

        ResponseEntity<Boolean> startResponse = restTemplate.postForEntity(
                baseUrl + "/test/load/response/start",
                null,
                Boolean.class
        );
    }

    private void stop() {
        final RestTemplate restTemplate = new RestTemplate();
        final String baseUrl = "http://localhost:8080";

        ResponseEntity<Boolean> startResponse = restTemplate.postForEntity(
                baseUrl + "/test/load/response/stop",
                null,
                Boolean.class
        );
    }

    public TestStompSession createSession() throws InterruptedException, ExecutionException, TimeoutException {
        SockJsClient sockJsClient = new SockJsClient(List.of(
                new WebSocketTransport(new StandardWebSocketClient())
        ));

        WebSocketStompClient stompClient = new WebSocketStompClient(sockJsClient);
        MappingJackson2MessageConverter messageConverter = new MappingJackson2MessageConverter(OBJECT_MAPPER);
        messageConverter.setStrictContentTypeMatch(false);
        stompClient.setMessageConverter(messageConverter);
        String url = String.format(WEBSOCKET_BASE_URL_FORMAT, 8080);
        StompSession session = stompClient
                .connectAsync(
                        url, new StompSessionHandlerAdapter() {

                            @Override
                            public void handleTransportError(StompSession session, Throwable exception) {
                                throw new RuntimeException(exception);
                            }

                            @Override
                            public void handleException(
                                    StompSession session,
                                    StompCommand command,
                                    StompHeaders headers,
                                    byte[] payload,
                                    Throwable exception
                            ) {
                                throw new RuntimeException(exception);
                            }
                        }
                )
                .get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return new TestStompSession(session, OBJECT_MAPPER);
    }
}
