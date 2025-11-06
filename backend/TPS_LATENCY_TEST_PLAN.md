# WebSocket Inbound/Outbound TPS 비율에 따른 레이턴시 테스트 계획

## 1. 현재 구조

### 1.1 구현된 내용
- **Test 패키지**: RequestDTO, ResponseDTO, ResponseService, WebSocketController
- **WebSocket 설정**: Inbound 32/2048, Outbound 16/4096
- **메트릭**: inbound/outbound 처리 시간, 큐 대기 시간, 비즈니스 로직 시간 (P95, P99)

### 1.2 한계
- Outbound TPS 고정 (10 TPS)
- Inbound 처리 로직 없음
- 다양한 TPS 비율 테스트 불가

---

## 2. 구현할 기능

### 2.1 서버 측 기능
1. **동적 TPS 제어 API**
   - ResponseService에 `setTps(int tps)` 추가
   - `POST /test/load/config?outboundTps={value}`

2. **Inbound 메시지 처리 로직**
   - WebSocketController의 `load()` 메서드에 처리 로직 추가
   - 비즈니스 로직 시뮬레이션 (CPU/IO/Hybrid)

3. **부하 설정**
   ```java
   public enum LoadType {
       NONE, CPU_BOUND, IO_BOUND
   }
   ```
   - `POST /test/load/config`
   ```json
   {
     "outboundTps": 10,
     "loadType": "NONE",
     "loadDurationMs": 0
   }
   ```

4. **메트릭 조회 API**
   - `GET /test/metrics`: 현재까지 수집된 메트릭 반환
   - 기존 Micrometer 메트릭 + 추가 메트릭

### 2.2 추가 메트릭
기존 WebSocketMetricService에 추가:
- TPS 측정 (1초 단위 카운터)
- 스레드풀 상태 (active threads, queue size)

### 2.3 JUnit 테스트 클라이언트
기존 `WebSocketIntegrationTestSupport` 및 `TestStompSession` 활용

---

## 3. 테스트 시나리오

### 3.1 TPS 비율별 테스트 (8개 시나리오)

| 시나리오 | Inbound TPS | Outbound TPS | 목적 |
|---------|-------------|--------------|------|
| 1 | 10 | 10 | 기준선 |
| 2 | 50 | 10 | Inbound 부하 |
| 3 | 100 | 10 | Inbound 포화 |
| 4 | 10 | 50 | Outbound 부하 |
| 5 | 10 | 100 | Outbound 포화 |
| 6 | 100 | 100 | 양방향 부하 |
| 7 | 500 | 10 | 극한 Inbound |
| 8 | 10 | 500 | 극한 Outbound |

각 테스트:
- 60초 실행
- 10개 동시 연결
- 부하 없음(NONE)으로 시작

---

## 4. 구현 단계

### Phase 1: 서버 기능 (필수)
1. ResponseService에 동적 TPS 제어 추가
2. WebSocketController에 Inbound 처리 로직 추가
3. 부하 설정 API 구현
4. 메트릭 조회 API 구현

### Phase 2: JUnit 테스트 (필수)
1. LoadTestClient 구현
2. TpsRatioLatencyTest 구현 (@ParameterizedTest)
3. 테스트 실행 후 메트릭 조회 및 출력

### Phase 3: 결과 분석 (필수)
1. 메트릭 기반 결과 수집
2. CSV 파일로 저장
3. 결과 비교 및 분석

---

## 5. JUnit 테스트 구현

### 5.1 기존 패턴 활용
```java
// WebSocketIntegrationTestSupport.java
- createSession(): TestStompSession 생성
- TestStompSession.subscribe(): MessageCollector 반환
- MessageCollector.get(): MessageResponse (duration 포함)
```

### 5.2 LoadTestClient
**위치**: `src/test/java/coffeeshout/test/load/LoadTestClient.java`

```java
public class LoadTestClient {
    private final List<TestStompSession> sessions;
    private final ScheduledExecutorService scheduler;

    public LoadTestClient(int connectionCount, String wsUrl, ObjectMapper objectMapper);
    public void startSending(String endpoint, int tps, Supplier<Object> messageSupplier);
    public void stopSending();
    public void closeAll();
}
```

### 5.3 TPS 비율 테스트
**위치**: `src/test/java/coffeeshout/test/load/TpsRatioLatencyTest.java`

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({IntegrationTestConfig.class})
class TpsRatioLatencyTest extends WebSocketIntegrationTestSupport {

    @LocalServerPort
    private int port;

    @ParameterizedTest(name = "시나리오 {index}: Inbound {0} TPS, Outbound {1} TPS")
    @CsvSource({
        "10, 10",
        "50, 10",
        "100, 10",
        "10, 50",
        "10, 100",
        "100, 100",
        "500, 10",
        "10, 500"
    })
    void TPS_비율별_레이턴시_테스트(int inboundTps, int outboundTps) throws Exception {
        // Given: 서버 설정
        configureServer(outboundTps, LoadType.NONE, 0);

        // Given: 클라이언트 생성
        LoadTestClient client = new LoadTestClient(10, port, objectMapper);

        // When: 부하 테스트 실행 (60초)
        runLoadTest(client, inboundTps, 60);

        // Then: 메트릭 조회 및 출력
        MetricsResponse metrics = getMetrics();
        printResults(inboundTps, outboundTps, metrics);
        saveResults(inboundTps, outboundTps, metrics);
    }

    private void configureServer(int outboundTps, LoadType loadType, int loadDurationMs) {
        // POST /test/load/config
    }

    private void runLoadTest(LoadTestClient client, int inboundTps, int durationSeconds) {
        // 지정된 TPS로 메시지 전송
        client.startSending("/app/test/load/request", inboundTps,
            () -> RequestDTO.from(System.currentTimeMillis()));

        Thread.sleep(durationSeconds * 1000);
        client.stopSending();
    }

    private MetricsResponse getMetrics() {
        // GET /test/metrics 호출
    }

    private void printResults(int inboundTps, int outboundTps, MetricsResponse metrics) {
        System.out.printf("""
            ========================================
            Scenario: Inbound %d TPS, Outbound %d TPS
            ========================================
            Inbound Queue Time: P95=%.2f, P99=%.2f
            Outbound Queue Time: P95=%.2f, P99=%.2f
            Business Logic Time: P95=%.2f, P99=%.2f
            Thread Pool:
              Inbound Active: %d/32, Queue: %d/2048
              Outbound Active: %d/16, Queue: %d/4096
            ========================================
            """,
            inboundTps, outboundTps,
            metrics.inboundP95(), metrics.inboundP99(),
            metrics.outboundP95(), metrics.outboundP99(),
            metrics.businessLogicP95(), metrics.businessLogicP99(),
            metrics.inboundActiveThreads(), metrics.inboundQueueSize(),
            metrics.outboundActiveThreads(), metrics.outboundQueueSize()
        );
    }

    private void saveResults(int inboundTps, int outboundTps, MetricsResponse metrics) {
        // CSV 파일에 저장
    }
}
```

### 5.4 메트릭 응답 DTO
```java
public record MetricsResponse(
    double inboundP95,
    double inboundP99,
    double outboundP95,
    double outboundP99,
    double businessLogicP95,
    double businessLogicP99,
    int inboundActiveThreads,
    int inboundQueueSize,
    int outboundActiveThreads,
    int outboundQueueSize,
    long totalInboundMessages,
    long totalOutboundMessages
) {}
```

---

## 6. 결과 수집

### 6.1 메트릭 조회 엔드포인트
`GET /test/metrics` 응답 예시:
```json
{
  "inboundP95": 45.2,
  "inboundP99": 78.5,
  "outboundP95": 12.3,
  "outboundP99": 25.1,
  "businessLogicP95": 2.1,
  "businessLogicP99": 5.3,
  "inboundActiveThreads": 8,
  "inboundQueueSize": 120,
  "outboundActiveThreads": 5,
  "outboundQueueSize": 50,
  "totalInboundMessages": 6000,
  "totalOutboundMessages": 600
}
```

### 6.2 CSV 결과 파일
**위치**: `build/test-results/load-test/tps-ratio-results.csv`

```csv
InboundTPS,OutboundTPS,InboundP95,InboundP99,OutboundP95,OutboundP99,BusinessLogicP95,BusinessLogicP99,InboundActive,InboundQueue,OutboundActive,OutboundQueue
10,10,15.2,22.3,8.1,12.5,1.0,2.1,2,0,1,0
50,10,45.3,67.8,8.5,13.2,1.2,2.3,8,45,1,0
100,10,89.5,145.2,9.1,14.5,1.3,2.5,25,320,1,0
...
```

---

## 7. 구현 파일 위치

### 7.1 서버 측
- `src/main/java/coffeeshout/test/application/ResponseService.java` - TPS 제어
- `src/main/java/coffeeshout/test/ui/WebSocketController.java` - Inbound 처리
- `src/main/java/coffeeshout/test/application/LoadConfigRequest.java` - 설정 DTO
- `src/main/java/coffeeshout/test/application/MetricsResponse.java` - 메트릭 DTO
- `src/main/java/coffeeshout/test/ui/TestMetricsController.java` - 메트릭 조회

### 7.2 테스트
- `src/test/java/coffeeshout/test/load/LoadTestClient.java` - 부하 생성
- `src/test/java/coffeeshout/test/load/TpsRatioLatencyTest.java` - JUnit 테스트

---

## 8. 실행 순서

1. **서버 기능 구현** (Phase 1)
   - ResponseService.setTps()
   - WebSocketController.load() 처리 로직
   - POST /test/load/config
   - GET /test/metrics

2. **테스트 구현** (Phase 2)
   - LoadTestClient
   - TpsRatioLatencyTest

3. **테스트 실행 및 결과 수집** (Phase 3)
   ```bash
   ./gradlew test --tests "TpsRatioLatencyTest"
   ```
   - 8개 시나리오 자동 실행
   - 각 시나리오 종료 후 메트릭 조회
   - CSV 파일에 결과 저장
   - 콘솔에 결과 출력

4. **결과 분석**
   - CSV 파일 확인
   - TPS 비율에 따른 레이턴시 패턴 분석
   - 병목 지점 식별
