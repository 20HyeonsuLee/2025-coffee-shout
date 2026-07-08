# Lettuce 비동기 Redis Publish 분석

## 1. 비동기 요청은 어느 스레드에서 실행되는가?

### 현재 (동기 - RedisTemplate)

```
WebSocket Inbound Thread (32개)
    ↓ convertAndSend() — blocking
    ↓ Lettuce가 Netty EventLoop에 명령 전달
    ↓ Netty EventLoop이 Redis에 PUBLISH 전송
    ↓ Redis 응답 대기 (blocking) ← 여기서 Inbound Thread가 멈춤
    ↓ 응답 수신 후 Inbound Thread 반환
```

`RedisTemplate.convertAndSend()`는 내부적으로 Lettuce의 동기 API(`sync()`)를 사용한다.
Lettuce sync API는 내부적으로 `async().get()`을 호출하여 **호출 스레드(Inbound Thread)를 블로킹**한다.

### 비동기 (ReactiveRedisTemplate 또는 Lettuce async API)

```
WebSocket Inbound Thread (32개)
    ↓ publish() — non-blocking, 즉시 반환
    ↓ Lettuce가 Netty EventLoop에 명령 전달
    ↓ Inbound Thread는 즉시 다른 메시지 처리 가능

Netty EventLoop Thread (기본 Runtime.getRuntime().availableProcessors() 개)
    ↓ Redis에 PUBLISH 전송
    ↓ Redis 응답 수신
    ↓ CompletableFuture/Mono 콜백 실행 ← Netty EventLoop Thread에서 실행
```

**핵심**: 비동기로 보내면 실제 I/O와 콜백은 **Lettuce의 Netty EventLoop 스레드**에서 실행된다.
호출 스레드(Inbound Thread)는 `publish()` 호출 즉시 반환되어 다음 메시지를 처리할 수 있다.

### Netty EventLoop 스레드 수

- 기본값: `Runtime.getRuntime().availableProcessors()` (t4g.small = 2개)
- Lettuce는 단일 커넥션으로 다중 명령을 파이프라이닝하므로 EventLoop 스레드가 적어도 충분
- EventLoop 스레드에서 블로킹 작업을 하면 안 됨 (콜백에서 DB 접근 등 금지)

## 2. 성능 차이 예상

### 시나리오: Racing Game Tap 이벤트 (초당 100건)

| 구분 | 동기 (현재) | 비동기 |
|------|------------|--------|
| **Inbound Thread 점유 시간** | Redis RTT (1~2ms) + 직렬화 | 직렬화만 (~0.1ms) |
| **Thread 사용 효율** | 1 요청 = 1 Thread 점유 | 1 요청 = Thread 즉시 반환 |
| **동시 처리 가능 요청** | 최대 32개 (Thread Pool 크기) | Thread Pool에 의존하지 않음 |
| **Redis RTT 영향** | 직접적 (RTT × 요청 수) | 없음 (파이프라이닝) |

### 예상 성능 차이

**로컬 Redis (RTT ~0.1ms)**
- 차이 거의 없음. 블로킹 시간이 워낙 짧아 Thread 점유가 문제되지 않음.

**AWS ElastiCache (RTT 1~2ms)**
- 동기: Thread 32개 × (1ms RTT) = 이론적 최대 32,000 TPS
- 비동기: Netty EventLoop이 파이프라이닝으로 처리, Thread Pool 병목 제거
- **약 2~5배 처리량 향상** 기대 (Thread Pool 포화 상황에서)

**AWS ElastiCache (부하 시 RTT 5~10ms)**
- 동기: Thread 32개 × (10ms RTT) = 최대 3,200 TPS, **Thread Pool 포화 발생**
- 비동기: RTT와 무관하게 Thread 반환, **10배 이상 차이** 가능
- 이 시나리오에서 비동기 전환의 효과가 극대화됨

### 결론

| 조건 | 비동기 전환 효과 |
|------|----------------|
| 로컬 Redis | 거의 없음 |
| ElastiCache (정상) | 2~5배 |
| ElastiCache (부하/지연) | 10배+ |
| Virtual Thread 사용 시 | 동기도 충분 (VThread가 RTT 대기 처리) |

> **참고**: 현재 프로젝트에서 `websocket.inbound.virtual-threads=true`를 사용하면,
> Virtual Thread가 Redis RTT 대기 중 carrier thread를 양보하므로
> 동기 RedisTemplate으로도 비동기와 유사한 효과를 얻을 수 있다.
> 비동기 전환은 Platform Thread 환경에서 가장 효과적이다.
