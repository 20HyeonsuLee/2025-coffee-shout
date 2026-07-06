---
name: 0000-redis-zset-scheduler
status: accepted
task_id: 0000
branch: task/0000-redis-zset-scheduler
created: 2026-07-06
decided_at: 2026-07-06
supersedes: []
superseded_by:
host: claude
---

# Redis Sorted Set 분산 스케줄러 — 플레이어 지연 삭제 적용

## 문제

`DelayedPlayerRemovalService`의 grace period 타이머(15초)가 WAS 메모리에 있다
(`ConcurrentHashMap<playerKey, ScheduledFuture>` + `TaskScheduler`).

1. **WAS 장애 시 타이머 소멸** — 삭제가 영원히 실행되지 않아 유령 플레이어가 방에 잔류.
2. **멀티 WAS 취소 실패** — WAS-1에서 끊긴 플레이어(타이머는 WAS-1 메모리)가 WAS-2로
   재접속하면 WAS-2의 `cancelScheduledRemoval`은 로컬 map만 보므로 취소 불가.
   **재접속했는데도 15초 뒤 삭제됨.**

## 대안 비교

| | WAS 메모리 타이머 (기존) | Redis Keyspace Notification | Sorted Set + 폴링 (채택) |
|---|---|---|---|
| WAS 장애 시 | 타이머 소멸 | 유실 가능 (Pub/Sub 기반) | 스케줄 보존 |
| 중복 실행 | — | 전 WAS 수신 → 락 필요 | Lua 원자 소비로 불필요 |
| 다른 WAS에서 취소 | 불가 | 가능 | ZREM으로 가능 |
| 시각 정확도 | 정확 | lazy/active expire로 부정확 | 폴링 주기(1s) 오차 |
| 모델 | — | push (놓치면 복구 불가) | pull (데이터가 남음) |

Keyspace Notification 기각 근거: Pub/Sub 기반이라 재연결 순간의 이벤트는 유실되고,
expire 시각도 보장되지 않는다. push는 알림을 놓치면 끝이지만 pull은 다음 폴링이 회수한다.

## 결정

- ZSET `scheduler:tasks` — member=`{TYPE}:{id}`, score=실행 시각(epoch millis)
- STRING `scheduler:payload:{taskKey}` — 실행 payload (TTL로 고아 정리)
- 소비: Lua로 `ZRANGEBYSCORE` + `ZREM` + payload `GET/DEL`을 원자 실행.
  획득=삭제가 한 연산이므로 폴러 N개가 경쟁해도 중복 소비 불가 — 분산 락 불필요.
- `DelayedTaskScheduler` 인터페이스로 추상화, "test" profile은 in-memory fake
  (기존 `delayRemovalScheduler` TaskScheduler bean 패턴 대체).
- 소비 지연 계측: `scheduler.task.fire.delay` (예정 시각 대비 실제 실행 지연 p99).

## 보장 범위 (한계 명시)

- 전달: at-least-once (스케줄이 Redis에 보존, WAS 전멸 후에도 회수)
- 획득: exactly-once (원자 소비)
- **소비 직후 핸들러 실행 전 프로세스 crash 시 해당 작업 유실** — Sidekiq 기본 fetch와
  같은 트레이드오프. 필요 시 in-progress set(RPOPLPUSH 계열)으로 보강 가능.

## 스코프

PLAYER_REMOVAL 타입만 적용. 다른 타이머(cardgame/racinggame 게임 진행 타이머)는
게임 세션과 생명주기가 같아 WAS 메모리 유지가 타당 — 적용하지 않음.
