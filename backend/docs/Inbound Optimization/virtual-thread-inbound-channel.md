# WebSocket Inbound Channel 가상 스레드 도입

## 배경

WebSocket inbound 메시지 처리에서 가장 큰 지연 원인이 Redis publish 응답 대기(I/O 블로킹)였다.
기존 `ThreadPoolTaskExecutor`(플랫폼 스레드 8개)는 8개 전부 Redis I/O 대기에 걸리면 나머지 메시지가 큐에 쌓이며 tail latency가 급증했다.

Lettuce는 단일 커넥션 + Netty 멀티플렉싱 구조이므로, 동시 publish 수 = 호출 스레드 수다.
가상 스레드를 사용하면 Redis 응답 대기 시 캐리어 스레드를 반환하므로, 적은 OS 스레드로 많은 동시 publish를 처리할 수 있다.

## 메시지 처리 흐름

```
tomcat → inbound(가상스레드) → redis publish(I/O 대기) → redis subscribe → outbound → 클라이언트
```

## 구현

### 핵심 문제: `ChannelRegistration.taskExecutor()`가 `ThreadPoolTaskExecutor`만 수용

Spring의 `ChannelRegistration.taskExecutor()`는 `ThreadPoolTaskExecutor` 타입만 받는다.
`SimpleAsyncTaskExecutor`(가상 스레드)를 직접 전달할 수 없다.

### 시도한 접근들

| 접근 | 결과 |
|------|------|
| `@Bean clientInboundChannelExecutor()` 오버라이드 | `DelegatingWebSocketMessageBrokerConfiguration`이 나중에 처리되어 우리 Bean을 덮어씀 |
| `allow-bean-definition-overriding=true` | Bean 정의 순서 문제로 프레임워크 Bean이 우리 것을 덮어씀 |
| **`BeanPostProcessor`** | **프레임워크 Bean 생성 이후 가로채서 교체 - 순서 문제 없음** |

### 최종 구현: `BeanPostProcessor`

`InboundChannelExecutorConfig`에서 `BeanPostProcessor`를 등록하여 `clientInboundChannelExecutor` Bean을 가로챈다.

```java
// InboundChannelExecutorConfig.java
@Bean
public static BeanPostProcessor inboundChannelExecutorPostProcessor() {
    return new InboundChannelExecutorPostProcessor();
}
```

- `websocket.inbound.virtual-threads=true` → `SimpleAsyncTaskExecutor` + 가상 스레드
- `websocket.inbound.virtual-threads=false` → `ThreadPoolTaskExecutor` (기존 방식)
- `websocket.inbound.concurrency-limit` → 가상 스레드 동시 실행 상한

### 설정 (application.yml)

```yaml
websocket:
  inbound:
    virtual-threads: true    # false로 바꾸면 기존 스레드풀
    concurrency-limit: 64    # 가상 스레드 동시 실행 제한
```

## concurrencyLimit이 필요한 이유

가상 스레드는 기본적으로 동시성 제한이 없다.
`concurrencyLimit` 없이 실행하면 메시지 폭주 시 가상 스레드가 무제한 생성되고,
동시 Redis publish가 급증하여 힙 메모리가 고갈된다 (OOM 발생 확인됨).

```
concurrencyLimit 없음: 메시지 1000개 → 가상스레드 1000개 → Redis publish 1000개 동시 → OOM
concurrencyLimit=64:   메시지 1000개 → 가상스레드 64개 실행 + 936개 대기 → 안정적
```

내부적으로 세마포어처럼 동작하며, `ThreadPoolTaskExecutor`의 `maxPoolSize`가 하던 역할을 대신한다.

튜닝 기준은 Redis 커넥션 풀이 아니라 **힙 여유분**이다.
Lettuce는 단일 커넥션 멀티플렉싱이므로, 동시에 대기 중인 가상 스레드마다
요청/응답 객체가 힙에 올라간다. 힙 크기에 맞춰 값을 조절해야 한다.

## 검증

### 테스트: 실제 STOMP 메시지가 가상 스레드에서 처리되는지 확인

`InboundChannelExecutorConfigTest`에서 검증:

1. `clientInboundChannel`에 `ExecutorChannelInterceptor`를 등록
2. WebSocket 연결 후 STOMP 메시지 전송
3. `beforeHandle`에서 처리 스레드 캡처 (executor 스레드에서 실행됨)
4. `Thread.isVirtual() == true` 및 스레드 이름 `inbound-*` 확인

주의: `ChannelInterceptor.preSend`는 executor 디스패치 **전** 호출 스레드(Tomcat)에서 실행된다.
실제 executor 스레드를 캡처하려면 `ExecutorChannelInterceptor.beforeHandle`을 사용해야 한다.

```
=== Inbound 메시지 처리 스레드 검증 ===
Thread name: inbound-1
Thread isVirtual: true
```

## 메트릭 및 모니터링

### 기본 JVM 메트릭으로는 가상 스레드가 안 보인다

`ThreadMXBean`이 가상 스레드를 카운트하지 않으므로 `jvm.threads.live`, `jvm.threads.peak` 등에 가상 스레드가 포함되지 않는다.
`SimpleAsyncTaskExecutor`는 풀이 아니라 `executor.*` 메트릭도 제공하지 않는다.

### 부하 테스트 비교에 의미 있는 메트릭

| 메트릭 | 설명 |
|--------|------|
| 처리량 (msg/sec) | `WebSocketInboundMetricInterceptor`에서 수집 |
| 응답 지연 (p50, p95, p99) | 메시지 처리 소요 시간 |
| JVM 메모리 사용량 | Actuator + Prometheus 기본 수집 |
| CPU 사용률 | 컨텍스트 스위칭 비용 차이 |

### JFR (Java Flight Recorder)로 가상 스레드 프로파일링

`ENABLE_VT_PROFILING=true`로 서버를 시작하면 JFR이 활성화된다.

```bash
ENABLE_VT_PROFILING=true ./scripts/application_start-dev.sh
```

커스텀 JFC 파일(`scripts/virtual-thread-profile.jfc`)에서 활성화하는 이벤트:

| 이벤트 | 설명 |
|--------|------|
| `jdk.VirtualThreadStart` | 가상 스레드 생성 |
| `jdk.VirtualThreadEnd` | 가상 스레드 종료 |
| `jdk.VirtualThreadPinned` | 캐리어 스레드 고정 (1ms 이상) |
| `jdk.VirtualThreadSubmitFailed` | 가상 스레드 제출 실패 |

`-Djdk.tracePinnedThreads=short` 옵션도 함께 활성화되어, 핀닝 발생 시 stderr에 스택트레이스가 출력된다.

부하 테스트 후 분석:

```bash
# 핀닝 이벤트 확인 (가장 중요)
jfr print --events jdk.VirtualThreadPinned logs/jfr/load-test.jfr

# 가상 스레드 이벤트 전체 확인
jfr print --events jdk.VirtualThread* logs/jfr/load-test.jfr

# 요약
jfr summary logs/jfr/load-test.jfr
```

## 가상 스레드가 효과적인 조건

### 핵심 원리

가상 스레드는 I/O 대기 중 캐리어 스레드를 반환한다.
이 이점이 체감되려면 **"스레드는 많이 필요한데, 대부분 I/O 대기"** 상황이어야 한다.

### 조건 1: I/O 대기 시간이 길고, 동시 요청이 플랫폼 스레드 풀을 초과할 때

```
플랫폼 스레드 32개, Redis 응답 2ms:
  → 초당 처리량 = 32 / 0.002 = 16,000 msg/sec (충분 → 가상 스레드 불필요)

플랫폼 스레드 32개, 외부 API 응답 200ms:
  → 초당 처리량 = 32 / 0.2 = 160 msg/sec (부족 → 가상 스레드 효과적)
```

I/O 대기가 짧으면 플랫폼 스레드 풀이 고갈되지 않으므로 가상 스레드의 이점이 없다.

### 조건 2: 캐리어 스레드(CPU 코어)가 충분할 때

가상 스레드의 캐리어 풀 = `ForkJoinPool.commonPool()` = CPU 코어 수.

| 인스턴스 | vCPU | 캐리어 스레드 | 효과 |
|----------|------|--------------|------|
| t4g.small | 2 | 2 | 가상 스레드 수백 개여도 동시 실행은 2개. 마운트/언마운트 오버헤드만 추가됨 |
| t4g.xlarge | 4 | 4 | 제한적 개선 |
| c6g.2xlarge | 8 | 8 | 체감 차이 발생 |
| 16+ vCPU | 16+ | 16+ | 본격적 이점 |

### 전형적인 승리 시나리오

| 시나리오 | 이유 |
|----------|------|
| HTTP 서버 (수천 동시 요청) | 요청마다 DB/외부 API로 수십~수백ms 대기. 플랫폼 스레드 200개 한계 → 가상 스레드로 수만 동시 요청 처리 |
| 마이크로서비스 체이닝 | A → B → C → D 순차 호출, 각 50ms 대기. 총 200ms 블로킹으로 스레드 점유 시간이 김 |
| 대량 배치 I/O | 파일 수만 개 읽기, DB row 개별 insert 등 |

### 현재 환경(t4g.small)에서 불리한 이유

```
- 2 vCPU → 캐리어 스레드 2개 (플랫폼 스레드 32개보다 적음)
- Redis 응답 1~2ms → 짧은 I/O 대기 (플랫폼 스레드로 충분)
- 동시 WebSocket 메시지 < 32 → 스레드 풀 고갈 안 됨
```

인스턴스를 8+ vCPU로 올리거나, I/O 대기가 긴 작업(외부 API 호출 등)이 추가되면 재검토할 가치가 있다.

## 주의사항

### 핀닝 (Pinning)

가상 스레드가 `synchronized` 블록이나 네이티브 메서드 안에서 I/O 대기하면
캐리어 스레드에 고정(pinning)되어 가상 스레드의 이점이 사라진다.
Redisson/Lettuce 내부에 `synchronized` 블록이 있을 수 있으므로 반드시 JFR로 확인해야 한다.

### `@Primary` TaskScheduler 주의

`WebSocketSchedulerConfig`의 `customMessageBrokerTaskScheduler`에 `@Primary`가 붙어있다.
`ThreadPoolTaskScheduler`는 `TaskExecutor`도 구현하므로, `@Qualifier` 없이 `TaskExecutor`를 주입받는 곳에
이 1스레드 스케줄러가 들어가 병목이 될 수 있다.

## 변경 파일 목록

| 파일 | 변경 내용 |
|------|-----------|
| `InboundChannelExecutorConfig.java` | BeanPostProcessor로 inbound executor 교체 (신규) |
| `WebSocketMessageBrokerConfig.java` | @Bean 제거, 인터셉터만 등록 |
| `application.yml` | `websocket.inbound.virtual-threads`, `concurrency-limit` 추가 |
| `application_start-dev.sh` | JFR 프로파일링 옵션 추가 |
| `virtual-thread-profile.jfc` | 가상 스레드 JFR 이벤트 설정 (신규) |
| `InboundChannelExecutorConfigTest.java` | 가상 스레드 검증 테스트 (신규) |
