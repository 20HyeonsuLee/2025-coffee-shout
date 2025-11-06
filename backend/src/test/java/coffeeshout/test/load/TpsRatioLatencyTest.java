package coffeeshout.test.load;

import coffeeshout.fixture.WebSocketIntegrationTestSupport;
import coffeeshout.global.config.IntegrationTestConfig;
import coffeeshout.test.application.dto.LoadConfigRequest;
import coffeeshout.test.application.LoadType;
import coffeeshout.test.application.dto.MetricsResponse;
import coffeeshout.test.application.dto.InboundRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({IntegrationTestConfig.class})
class TpsRatioLatencyTest extends WebSocketIntegrationTestSupport {

    private static final Logger log = LoggerFactory.getLogger(TpsRatioLatencyTest.class);
    private static final String CSV_FILE_PATH = "build/test-results/load-test/tps-ratio-results.csv";

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void setupCsvFile() throws IOException {
        File file = new File(CSV_FILE_PATH);
        if (!file.exists()) {
            file.getParentFile().mkdirs();
            try (FileWriter writer = new FileWriter(file)) {
                writer.write("InboundTPS\tOutboundTPS\tInboundP95\tInboundP99\tOutboundP95\tOutboundP99\tBusinessLogicP95\tBusinessLogicP99\tInboundActive\tInboundQueue\tOutboundActive\tOutboundQueue\tTotalInbound\tTotalOutbound\n");
            }
        }
    }

    @ParameterizedTest(name = "시나리오 {index}: Inbound {0} TPS, Outbound {1} TPS")
    @CsvSource({
//            "10, 10",
//            "50, 10",
//            "100, 10",
//            "10, 50",
//            "10, 100",
//            "100, 100",
//            "500, 10",
            "2000, 2000"
    })
    void TPS_비율별_레이턴시_테스트(int inboundTps, int outboundTps) throws Exception {
        log.info("========================================");
        log.info("Starting test: Inbound {} TPS, Outbound {} TPS", inboundTps, outboundTps);
        log.info("========================================");

        // Given: 서버 설정 (TPS 및 부하 설정)
        configureServer(outboundTps, LoadType.NONE, 0);

        // 설정이 적용되도록 잠시 대기
        Thread.sleep(100);

        // Given: Outbound 응답 시작
        ResponseEntity<String> startResponse = restTemplate.postForEntity(
                "http://localhost:" + port + "/test/load/response/start",
                null,
                String.class
        );
        if (!startResponse.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("Failed to start response service: " + startResponse.getBody());
        }

        log.info("Response service started successfully");

        // Given: 클라이언트 생성
        LoadTestClient client = new LoadTestClient(10, port, objectMapper);

        try {
            // When: 부하 테스트 실행 (60초)
            runLoadTest(client, inboundTps, 60);

            // Then: 메트릭 조회 및 출력
            MetricsResponse metrics = getMetrics();
            printResults(inboundTps, outboundTps, metrics);
            saveResults(inboundTps, outboundTps, metrics);
        } finally {
            // Cleanup
            client.closeAll();
            restTemplate.postForEntity("http://localhost:" + port + "/test/load/response/stop", null, String.class);
        }

        log.info("Test completed: Inbound {} TPS, Outbound {} TPS", inboundTps, outboundTps);
    }

    private void configureServer(int outboundTps, LoadType loadType, int loadDurationMs) {
        LoadConfigRequest request = new LoadConfigRequest(outboundTps, loadType, loadDurationMs);
        ResponseEntity<Void> response = restTemplate.postForEntity(
                "http://localhost:" + port + "/test/load/config",
                request,
                Void.class
        );

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("Failed to configure server: " + response.getStatusCode());
        }

        log.info("Server configured: outboundTps={}, loadType={}, loadDurationMs={}",
                outboundTps, loadType, loadDurationMs);
    }

    private void runLoadTest(LoadTestClient client, int inboundTps, int durationSeconds) throws InterruptedException {
        log.info("Starting load test: {} TPS for {} seconds", inboundTps, durationSeconds);

        // 지정된 TPS로 메시지 전송 시작
        client.startSending("/app/test/load/request", inboundTps,
                () -> InboundRequest.from(System.currentTimeMillis()));

        // 지정된 시간만큼 대기
        Thread.sleep(durationSeconds * 1000L);

        // 전송 중지
        client.stopSending();

        log.info("Load test completed");
    }

    private MetricsResponse getMetrics() {
        ResponseEntity<MetricsResponse> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/test/metrics",
                MetricsResponse.class
        );

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new RuntimeException("Failed to get metrics: " + response.getStatusCode());
        }

        return response.getBody();
    }

    private void printResults(int inboundTps, int outboundTps, MetricsResponse metrics) {
        String results = String.format("""
                ========================================
                Scenario: Inbound %d TPS, Outbound %d TPS
                ========================================
                Inbound Queue Time: P95=%.2f ms, P99=%.2f ms
                Outbound Queue Time: P95=%.2f ms, P99=%.2f ms
                Business Logic Time: P95=%.2f ms, P99=%.2f ms
                Thread Pool:
                  Inbound Active: %d, Queue: %d
                  Outbound Active: %d, Queue: %d
                Total Messages:
                  Inbound: %d
                  Outbound: %d
                ========================================
                """,
                inboundTps, outboundTps,
                metrics.inboundP95(), metrics.inboundP99(),
                metrics.outboundP95(), metrics.outboundP99(),
                metrics.businessLogicP95(), metrics.businessLogicP99(),
                metrics.inboundActiveThreads(), metrics.inboundQueueSize(),
                metrics.outboundActiveThreads(), metrics.outboundQueueSize(),
                metrics.totalInboundMessages(), metrics.totalOutboundMessages()
        );

        log.info(results);
        System.out.println(results);
    }

    private void saveResults(int inboundTps, int outboundTps, MetricsResponse metrics) {
        try (FileWriter writer = new FileWriter(CSV_FILE_PATH, true)) {
            String line = String.format("%d\t%d\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f\t%d\t%d\t%d\t%d\t%d\t%d\n",
                    inboundTps, outboundTps,
                    metrics.inboundP95(), metrics.inboundP99(),
                    metrics.outboundP95(), metrics.outboundP99(),
                    metrics.businessLogicP95(), metrics.businessLogicP99(),
                    metrics.inboundActiveThreads(), metrics.inboundQueueSize(),
                    metrics.outboundActiveThreads(), metrics.outboundQueueSize(),
                    metrics.totalInboundMessages(), metrics.totalOutboundMessages()
            );
            writer.write(line);
            log.info("Results saved to {}", CSV_FILE_PATH);
        } catch (IOException e) {
            log.error("Failed to save results to CSV", e);
        }
    }
}
