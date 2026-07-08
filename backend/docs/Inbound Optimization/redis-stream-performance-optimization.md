# Redis Stream Inbound 병목 분석 및 Non-blocking I/O 전환으로 성능 개선

## 1. 문제 발견: Inbound만 유독 느리다

부하 테스트 중 Inbound 레이턴시가 Outbound 대비 비정상적으로 높은 현상을 발견했다.

### Thread 32 + Blocking (300ms 간격 부하)

| 메트릭 | Inbound | Outbound |
|--------|---------|----------|
| **p95** | **201ms** | 10.5ms |
| **p99** | **201ms** | 23.1ms |
| **avg** | **72.1ms** | 7.55ms |

Inbound p95가 Outbound p95보다 **약 20배 느리다.**

만약 WebSocket 자체의 문제라면 동일한 WebSocket 위에서 동작하는 Outbound도 느려야 한다. 그러나 Outbound는 p95 10.5ms로 정상 처리되고 있었다. WebSocket이 아닌 **Inbound 처리 경로에 병목**이 있다는 뜻이다.

## 2. 첫 번째 시도: 스레드 수 증가 — 실패

Inbound에서 유일하게 다른 점은 Redis Stream에 `XADD`로 메시지를 발행하는 부분이었다. I/O 작업이 포함되어 있으니 스레드를 늘리면 동시 처리량이 올라갈 것이라 판단하여, 기본 스레드 수에서 32개까지 늘렸다.

그러나 **큐 모니터링 결과, 32개 스레드에서도 큐가 주기적으로 쌓였다가 비워지는 톱니(sawtooth) 패턴**이 반복되었다.

| 모니터링 지표 | 값 |
|-------------|-----|
| Queue Max | **236** |
| Queue 패턴 | 0 → 236 → 0 → 200 반복 (톱니) |
| Inbound p95 | **201ms** (개선 안 됨) |

스레드를 늘렸음에도 큐에 지속적으로 메시지가 적체되었다. 특히 t4g.small(vCPU 2개)에서 32개 스레드가 경쟁하면서 Load Average가 **CPU 대비 326%**까지 치솟았다. 스레드를 늘린 것이 오히려 **컨텍스트 스위칭 오버헤드를 증가**시킨 것이다.

## 3. 근본 원인: RedisTemplate의 Blocking I/O

스레드를 늘려도 개선이 없는 이유를 파악하기 위해, Inbound와 Outbound의 처리 흐름을 비교했다.

```
[Inbound 흐름]
WebSocket 메시지 수신
  → Inbound Thread에서 처리
  → Redis Stream XADD (Blocking I/O) ← 여기서 스레드가 멈춤
  → Redis 응답 대기 (1~2ms)
  → 스레드 반환

[Outbound 흐름]
Redis Stream Consumer (별도 백그라운드 스레드)
  → 비즈니스 로직 처리
  → WebSocket 전송 (비동기)
  → 즉시 반환
```

**핵심 차이**: Inbound에만 Redis Stream `XADD`라는 Blocking I/O가 존재한다.

Spring Data Redis의 `RedisTemplate`은 내부적으로 Lettuce의 동기 API(`sync()`)를 사용한다. Lettuce의 `sync()` API는 내부적으로 `async().get()`을 호출하여 호출 스레드를 블로킹한다.

이것이 톱니 패턴의 원인이었다:
1. 32개 스레드가 동시에 `XADD`를 호출하면 전부 Redis 응답을 기다리며 블로킹
2. 이 동안 새로 들어오는 메시지는 큐에 쌓임 (큐 증가)
3. Redis 응답이 오면 스레드들이 한꺼번에 깨어나 큐를 비움 (큐 감소)
4. 다시 블로킹 → 반복

**스레드를 아무리 늘려도 모든 스레드가 I/O 대기 상태에 빠지는 구간이 존재하므로**, 큐 적체를 근본적으로 해결할 수 없었다.

## 4. 해결 방안 비교

| 방안 | 장점 | 단점 |
|------|------|------|
| **Virtual Thread** | 기존 블로킹 코드 그대로 사용, carrier thread 자동 양보 | Lettuce/Redisson 내부 `synchronized`에서 핀닝 발생 가능, 스레드 풀 메트릭 계측 불가 |
| **Lettuce Non-blocking** | Lettuce EventLoop이 I/O 직접 처리, Inbound 스레드 즉시 반환 | 에러 처리가 콜백 기반, 디버깅 난이도 증가 |

### Virtual Thread를 선택하지 않은 이유

- **핀닝(Pinning)**: Lettuce 내부의 Netty가 `synchronized` 블록을 사용한다. Virtual Thread가 이 구간에서 carrier thread를 점유하면 핀닝이 발생하여, Platform Thread와 동일하게 블로킹된다.
- **모니터링**: Virtual Thread는 기존 `ThreadPoolTaskExecutor` 메트릭으로 계측이 안 된다. 커스텀 메트릭을 별도 구현해야 한다.
- **Redisson 호환성**: 프로젝트에서 분산 락으로 사용 중인 Redisson도 내부적으로 `synchronized`를 사용하여 핀닝 위험이 있다.

### Lettuce Non-blocking을 선택한 이유

Lettuce는 이미 Netty 기반 비동기 아키텍처로 설계되어 있다. `RedisTemplate`이 이를 동기로 래핑하여 사용하고 있었을 뿐이다. Lettuce의 네이티브 비동기 API를 직접 사용하면:
- 추가 라이브러리 없이 기존 인프라 활용
- Inbound 스레드가 I/O를 전혀 수행하지 않게 됨
- 단일 커넥션 멀티플렉싱으로 커넥션 풀 부담 감소

## 5. 구현: Lettuce EventLoop 활용

### Before (Blocking)
```java
// RedisTemplate 내부: Lettuce sync API → async().get() → 호출 스레드 블로킹
stringRedisTemplate.opsForStream().add(record, xAddOptions);
```

### After (Non-blocking)
```java
// Lettuce native async API → Netty EventLoop에서 I/O 처리, 호출 스레드 즉시 반환
asyncCommands.xadd(streamKey, xAddArgs, "payload", eventJson)
    .whenComplete((messageId, throwable) -> {
        if (throwable != null) {
            log.error("발송 실패: streamKey={}", streamKey, throwable);
        }
    });
```

**동작 방식 변화:**
```
[Before]
Inbound Thread → serialize → XADD → [1~2ms 블로킹 대기] → 반환

[After]
Inbound Thread → serialize → asyncCommands.xadd() → [즉시 반환, ~0.1ms]
Netty EventLoop → Redis 전송 → 응답 수신 → 콜백 실행
```

Inbound 스레드가 I/O를 전혀 하지 않으므로, 순수 CPU 작업만 수행하는 스레드가 된다.

## 6. 성능 개선 결과

### 동일 조건(300ms 간격, Thread 32) 비교

| 메트릭 | Blocking | Non-blocking | 개선율 |
|--------|----------|-------------|--------|
| **Inbound p95** | 201ms | **62.9ms** | **3.2배 개선** |
| **Inbound p99** | 201ms | **117ms** | **1.7배 개선** |
| **Inbound avg** | 72.1ms | **24.1ms** | **3배 개선** |
| Queue Max | 236 (톱니 반복) | 85 (간헐적 스파이크) | **2.8배 감소** |

큐 모니터링에서 Blocking의 톱니 패턴이 사라지고, Non-blocking에서는 간헐적인 소규모 스파이크만 발생했다. 이는 Inbound 스레드가 더 이상 I/O로 블로킹되지 않아 큐가 안정적으로 소비되고 있음을 의미한다.

### 스레드 수 최적화

Non-blocking 전환 후, Inbound 스레드가 순수 CPU 작업만 수행하므로 스레드 수를 줄일 수 있었다. vCPU 2개 환경에서 과도한 스레드는 컨텍스트 스위칭 오버헤드만 증가시킨다.

| 구성 | Inbound p95 | Inbound avg | Queue Max |
|------|-------------|-------------|-----------|
| Non-blocking 32T | 62.9ms | 24.1ms | 85 |
| **Non-blocking 16T** | **62.9ms** | **24.8ms** | **119** |
| Non-blocking 8T | 52ms | 46.1ms | 56 |

- **16T**: p95가 32T와 동일(62.9ms). 스레드를 절반으로 줄여도 성능 저하 없음.
- **8T**: p95는 52ms로 오히려 좋아졌으나, avg가 46.1ms로 상승. 스레드 수가 너무 적어 피크 구간에서 처리가 밀리면서 평균이 올라간 것으로, vCPU 2개 환경의 하한선에 가까움.

**최적값: 16T** — p95 유지, 컨텍스트 스위칭 절반 감소, 안정적 운영.

## 7. 결론

| 항목 | Before | After |
|------|--------|-------|
| 방식 | RedisTemplate (Blocking) | Lettuce Async (Non-blocking) |
| Inbound p95 | 201ms | **62.9ms** |
| 스레드 수 | 32개 (늘려도 개선 없음) | **16개로 충분** |
| 큐 적체 | 최대 236, 톱니 패턴 | 최대 85, 안정적 |
| 핵심 원인 | XADD Blocking I/O가 스레드 점유 | Lettuce EventLoop이 I/O 분리 |

**Blocking I/O 병목을 제거하고 Lettuce의 비동기 아키텍처를 활용함으로써, 스레드 수를 절반으로 줄이면서도 Inbound 레이턴시를 3배 이상 개선했다.**
