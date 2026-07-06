# 분산 스케줄러 부하테스트 보고서 — 플레이어 지연 삭제

작성일: 2026-07-06
대상 구현: `task/0000-redis-zset-scheduler` (Redis ZSET + 1s 폴링 + Lua 원자 소비)

## 결론

WAS 메모리 타이머를 Redis Sorted Set 스케줄러로 교체한 뒤, 멀티 WAS 환경에서 세 가지를 수치로 검증했다.

1. **중복·유실 0**: disconnect storm 220건 등록 = 취소 60 + 소비 160, 잔여 0. 폴러 2개(각 1s)가 경쟁해도 Lua 원자 소비(획득=삭제)로 중복 실행 없음.
2. **WAS 사망 생존**: 스케줄 140건을 등록한 WAS를 grace period 중간에 `docker stop` — 스케줄은 Redis에 잔존, 생존 WAS가 취소 60(cross-WAS cancel) + 소비 140 전량 처리. 잔여 0, 실패 0.
3. **시각 정확도**: fire delay(예정 시각 대비 실제 실행) p50 0.20s / p95 0.78s / p99 0.80s / max 0.85s — 폴링 주기 1s 이내.

## 실험 환경

- WAS 2대 (docker compose, `SPRING_PROFILES_ACTIVE=docker`) + Nginx round-robin + Redis 7.2 + Prometheus/Grafana
- 부하: Artillery `player-removal-storm.yml` — 20방 × 8명(host 1 + guest 7), playerKey 바인딩 WebSocket 연결
- 시나리오: guest 140명 일괄 disconnect → 5초 뒤 방당 3명(총 60명) 재접속(grace 15s 이내) → 잔여 80명은 15초 뒤 자동 삭제

## 실험 1. Disconnect/Reconnect Storm (정상 경로)

| 지표 | 값 | 해석 |
|---|---:|---|
| scheduler_task_scheduled_total | 220 | guest 140 + 종료 시 재절단 80 (host 20 + 재접속 guest 60) |
| scheduler_task_cancelled_total | 60 | 재접속 60명 전원 취소 성공 |
| scheduler_task_consumed_total | 160 | 220 − 60, 정확히 일치 |
| scheduler_task_failed_total | 0 | |
| 잔여 (등록−취소−소비) | **0** | 유실 0 · 중복 0 |
| fire delay p50 / p95 / p99 / max | 0.20s / 0.78s / 0.80s / 0.81s | 폴링 주기 1s 이내 |

캡처: `grafana_screenshots/scheduler-dashboard-normal-storm.png`

## 실험 2. WAS 사망 — "타이머가 서버랑 같이 죽지 않는다"

절차: disconnect storm으로 owner WAS(app-1)에 140건 등록 확인 → 재접속 취소 진행 중 `docker stop backend-app-1-1` (grace period 중간, 소비 0 시점).

| 관측 | 값 | 해석 |
|---|---:|---|
| victim(app-1) 사망 직전 | scheduled 140, consumed 0 | WAS 메모리 타이머였다면 140건 전부 소멸되는 시점 |
| 사망 시점 Redis `ZCARD scheduler:tasks` | 80 | 취소 60 반영 후 스케줄이 Redis에 잔존 |
| survivor(app-2) cancelled | 60 | 재접속이 nginx로 app-2에 붙어 **다른 WAS가 등록한 스케줄을 취소** — 기존 로컬 map 구조에선 불가능했던 경로 |
| survivor(app-2) consumed | 140 | victim 등록분 80 + 종료 시 재절단 60 전량 소비 |
| survivor failed / Redis 잔여 | 0 / 0 | |
| survivor fire delay max | 0.85s | 인계 후에도 폴링 주기 이내 |

캡처: `grafana_screenshots/scheduler-dashboard-was-kill.png` — app-1 소비선이 60에서 끊기고 app-2 소비선이 140까지 상승하는 구간이 보임.

주의(캡처 해석): 스탯 패널 "잔여 −140"은 app-1 사망으로 그 카운터 시계열이 stale 처리된 Prometheus 집계 아티팩트다. 실제 잔여는 `ZCARD scheduler:tasks = 0`으로 검증했다.

원시 로그: `waskill-artillery.log`, 실험 스크립트 출력은 아래.

```
[t-disconnect] scheduled: app-1=140 app-2=0
[t-kill] victim=backend-app-1-1 사망 직전: scheduled=140 cancelled=0 consumed=0
[t-kill] redis zcard: 80
=== 최종 ===
survivor(app-2): scheduled=60 cancelled=60 consumed=140 failed=0
redis 잔여 작업: 0
fire delay: count=140 sum=103.484s max=0.851s
```

## 실험 3. 폴러 경쟁 관찰

Redis MONITOR 4초 샘플: app-1(172.20.0.4), app-2(172.20.0.5)가 각각 1s 주기로 소비 Lua(`ZRANGEBYSCORE`+`ZREM`) 실행 — 위상차 ~0.45s. due 시각이 버스트로 몰리면 위상이 앞선 폴러가 배치(100)로 선점한다. 분산은 위상 우연에 좌우되지만, **어느 쪽이 가져가든 정확히 한 번**이라는 불변식은 Lua 원자 소비가 보장한다 (실험 1·2의 잔여 0).

## 부수 발견

1. **기존 load-test는 playerKey 바인딩이 안 됨** — `connect-websocket.js`의 `connectHeaders: {}`로는 서버가 세션↔플레이어를 연결하지 못해 disconnect 이벤트가 발생하지 않는다. 본 시나리오는 `removal-storm.js`에서 `joinCode/playerName` 헤더를 포함해 연결한다.
2. **SockJS × round-robin 파리티 락** — SockJS 연결당 HTTP 요청이 2개(info + upgrade)라 순차 연결 시 upgrade가 전부 같은 backend로 몰린다. 연결 분산이 필요한 시나리오에선 `least_conn` 고려.
3. **Grafana datasource uid 미지정** — provisioning에 uid가 없어 재생성 시 랜덤 uid가 되고, `uid: "prometheus"`를 참조하는 기존 대시보드 전체가 "Data source not found"로 깨진다. `datasource.yml`에 `uid: prometheus` 명시로 fix.
4. **사망 WAS의 세션은 disconnect 이벤트 자체가 없다** — app-1이 죽으면 그 위의 세션(예: host 20명)은 disconnect 감지 주체가 없어 grace 삭제가 등록되지 않는다. 스케줄러 밖의 문제로, heartbeat/lease 기반 세션 소유권 감지가 후속 과제.

## 이력서 claim (수치 확보됨)

> "베팅 마감·재접속 grace 같은 시간 기반 로직이 WAS 메모리 타이머에 있으면 서버 장애와 함께 소멸하는 문제를, Redis Sorted Set(score=실행시각) + 폴링 + Lua 원자 소비(획득=삭제)로 전환해 해결 — 2 WAS 부하테스트에서 등록 220 = 취소 60 + 소비 160(잔여 0, 중복 0), 스케줄 등록 WAS를 grace period 중간에 kill해도 생존 WAS가 전량 인계(140/140), fire delay p99 0.80s(폴링 주기 1s 이내)를 계측 (로컬 2-WAS 환경)"

선례 매핑: Sidekiq `scheduled.rb`(zrange byscore + zrem Lua)와 동일 설계, Airbnb Dynein(±10s SLA)·카카오페이 지연이체(5분 폴링 + 상태 전이)와 같은 pull 계열.
