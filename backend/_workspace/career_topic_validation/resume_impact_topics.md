# 이력서 임팩트 토픽 종합 — 아키텍처 / 분산 스케줄링 / Thundering Herd

작성일: 2026-07-06
근거: 로컬 evidence(`portfolio_report.ko.md`, `sprint_4/02_implementation_report.md`, `redis_pubsub_consistency_topic_validation.md`, `debate_report.md`) + 업계 사례 웹 리서치 3건.

---

## 1. 세 주제 판정

| 주제 | 현재 상태 | 업계 공감대 | 이력서 사용 가능 시점 | 판정 |
|---|---|---|---|---|
| 아키텍처(Redis SSOT + versioned snapshot recovery) | 구현 + 수치 확보 (gap 17 = resync 17, 전파 p95 2.07ms) | Figma·Discord·Slack·배민 선례 직결 | **지금** (측정 환경 표기 조건) | GO — 단, "아키텍처 소개"가 아니라 "실패 모델 + 복구" 서사로 |
| Thundering herd (snapshot resync herd guard) | **수치 확보 완료** (2026-07-06): gap 133, read 98, coalesced 76, **증폭률 0.737**, failed 0 | **최대** — 국내 면접 표준 질문(매일메일 #132), Facebook lease·LINE req-shield·Slack Flannel 직결 | **지금** | GO |
| 분산 스케줄링 (Sorted Set + 폴링 + 원자 소비) | **구현 + 수치 확보 완료** (PR #1 + `scheduler_evidence/scheduler_load_test_report.md`): 등록 220=취소 60+소비 160 잔여 0, WAS kill 인계 140/140, fire delay p99 0.80s | Sidekiq 내부 구현과 코드 수준 동일, Airbnb Dynein·카카오페이 지연이체 선례 | **지금** | GO |

핵심 판단: 주제 1과 3은 별개 항목이 아니라 **한 서사의 본편과 속편**이다. "복구 경로를 만들었다(주제1) → 복구 경로 자체가 새 병목이 됐다(주제3)". 이 연결이 개별 나열보다 강하다.

---

## 2. "뻔함 → 번뜩임" 전환 원칙 (리서치 파트 B 종합)

식상함의 공통 원인 = **기술명이 주어인 문장** ("Redis를 도입했다", "분산 락을 적용했다").
번뜩임의 공통 구조 = **문제가 주어인 문장** + 아래 3요소.

| # | 요소 | 적용 방법 | 근거 |
|---|---|---|---|
| 1 | 전/후 수치 1쌍 | gap 17→resync 17, 재조회 N건→1건, 증폭률 x.x | 토스 채용 가이드·원티드·99CON 전부 일치. "심사관이 도박하게 만들지 마라"(이동욱) |
| 2 | 선례 매핑 1개 | "Facebook memcache lease와 동일 계열", "Sidekiq scheduled.rb와 동일 설계를 요구사항에서 재도출" | 시니어가 아는 이름 하나면 나머지를 자동 연상 |
| 3 | 대안 비교 + 폐기 근거 | "Discord는 replay 기본 + snapshot fallback. 방 상태가 작아 fallback을 유일 경로로 단순화" / "Keyspace Notification은 push라 유실 시 복구 불가 → pull로 전환" | 원티드 명시("장단점 구체적으로 = 높은 이해도"), 당근 면접 구조 일치 |

용어 선택 (시니어 인식 최우선 조합):
- 문제 명명: **thundering herd (cache stampede)** — 국내외 인지도 최상
- 해법 명명: **single-flight 요청 병합(request coalescing) + cooldown**
- 스케줄링: **durable timer**(Cadence/Temporal 용어), **at-least-once 전달 + 원자적 소비 = exactly-once 실행 효과**(Dropbox ATF 스펙 구조)

---

## 3. 이력서 Bullet 초안

### 지금 쓸 수 있는 것 (주제 1 — 정합성 recovery)

> 멀티 WAS + Redis Pub/Sub 실시간 방 상태 전파에서 메시지 중복·역전·유실로 화면이 어긋나는 문제를 부하 테스트로 재현하고, Pub/Sub을 durable log가 아닌 알림 채널로 제한한 뒤 roomVersion 기반 stale drop·gap 감지·스냅샷 재동기화를 구현 — 2 WAS·20방·상태 변경 2,800건 시나리오에서 gap 17회 전량 자동 복구(resync 17회), 전파 p95 2.07ms·복구 평균 3.02ms 계측 (로컬 멀티 WAS 환경)

- 면접 확장: "Discord Gateway는 seq 기반 replay가 기본이고 full snapshot이 fallback. 우리 방 상태는 4~10명 규모라 replay 버퍼 유지 비용 > 스냅샷 비용 → fallback을 유일 경로로 단순화" + "배민 Last-Event-ID는 주문 이벤트 자체가 업무 단위라 replay가 맞고, 우리는 최신 상태만 맞으면 되는 상태 지향 도메인이라 snapshot이 맞다"
- 클라이언트 stale drop 미구현 → "서버 subscriber 방어"까지만 주장. "서버+클라 양쪽 검증" 금지.

### 주제 3 — thundering herd (수치 확정, 2026-07-06)

> gap·재접속·WAS 재시작이 겹치면 같은 방 참가자 전원의 스냅샷 재조회가 Redis hot key로 몰리는 복구 경로의 thundering herd를 식별하고, 방 단위 single-flight 요청 병합 + version-aware cooldown + jitter/backoff를 구현 — 2 WAS ready storm + WAS 재시작 시나리오에서 gap 감지 133건 대비 snapshot read 98건(**full-sync 증폭률 0.737**, coalesced 76건), 복구 실패 0, Pub/Sub 전파 p95 1.84ms를 Grafana로 계측 (로컬 2-WAS 환경)

- 차별화 포인트 (일반 cache stampede와 다른 점): ① 대상이 캐시가 아니라 **복구 경로 자체** — "coalescing은 캐시 최적화가 아니라 recovery path 보호 장치" ② 단순 coalescing이 아니라 **version-aware** — 더 높은 version 합류 시 한 번 더 읽어 최신성 보전. 이 두 개가 "토스 블로그 읽고 따라한 것"과 구분되는 지점.
- 선례 매핑: Facebook memcache lease(키당 10초 1회 발급 = cooldown 동형, 17K→1.3K qps), LINE req-shield(+55% TPS), Slack Flannel(WebSocket reconnect storm — 도메인까지 동일).

### 주제 2 — 분산 스케줄링 (구현 + 수치 확정, 2026-07-06)

> 재접속 grace period(15초) 타이머가 WAS 메모리에 있어 서버 장애 시 소멸하고 다른 WAS로 재접속하면 취소가 실패하는 문제를, Redis Keyspace Notification(push — Pub/Sub 유실 + 전 WAS 중복 수신 + expire 시각 비보장) 기각 후 Sorted Set(score=실행시각) + 폴링 + Lua 원자 소비(획득=삭제, pull)로 전환 — 2 WAS 부하테스트에서 등록 220 = 취소 60 + 소비 160(잔여 0, 중복 0), 스케줄 등록 WAS를 grace period 중간에 kill해도 생존 WAS가 취소·소비 전량 인계(140/140), fire delay p99 0.80s(폴링 주기 1s 이내) 계측 (로컬 2-WAS 환경)

- 차별화 각도: "Sidekiq scheduled.rb의 Lua(zrange byscore + zrem)와 동일 설계를 프레임워크 없이 요구사항 분석으로 재도출" — 프레임워크 사용자와 이해자를 가르는 한 줄.
- 방어 준비: Quartz clustering은 cluster-wide DB lock이라 3노드 초과부터 성능 저하(공식 문서 명시) → "그냥 Quartz 쓰지"에 대한 반박. Celery ETA는 visibility_timeout 초과 시 중복 6회 배달 사례 → "broker 재배달에 지연 실행 얹으면 안 되는 이유".
- 폴링 스케일링 질문 대비: Sidekiq은 폴링 주기를 프로세스 수에 비례해 늘리고 jitter로 분산 → 클러스터 전체 폴링 rate 일정 유지.

---

## 4. 예상 꼬리질문 방어표

| 질문 | 답 |
|---|---|
| 락 잡은(coalescing 중) 요청이 죽으면? | retry/backoff + `room_snapshot_resync_failed_total` 계측. bounded retry 후 실패 노출 |
| gap detection이 못 잡는 유실은? | 마지막 이벤트 유실 + 후속 이벤트 없음 → gap 안 보임. reconnect/subscribe 초기 snapshot + periodic probe로 bounded stale duration 문제로 격하 (red-team 반영 사항) |
| cooldown 값 근거는? | 250ms — Facebook lease의 "키당 10초 1회"와 같은 계열. 부하테스트에서 증폭률/최신성 트레이드오프로 조정 |
| 인스턴스 내 coalescing vs 분산 락? | 인스턴스 내 single-flight만으로 herd가 인스턴스 수 분의 1로 감쇄. 전역 1회가 필요한 write는 Redisson 분산 락이 별도 담당 — 층 분리 |
| Kafka/Stream 안 쓴 이유? | SSOT가 Redis snapshot이라 메시지 보관 불필요. 이벤트 리플레이가 복구 수단이 아님 → 보관 기능이 관리 비용만 추가 |
| exactly-once 보장하나? (스케줄러) | 전달은 at-least-once. 획득=삭제 원자 연산 + 멱등 처리로 exactly-once **실행 효과**. Dropbox ATF의 이중 보장 스펙과 동일 구조 |

---

## 5. 선례 매핑 (원문 URL)

| 내 설계 요소 | 선례 | URL |
|---|---|---|
| 재연결 = 스냅샷 재다운로드 | Figma multiplayer | https://www.figma.com/blog/how-figmas-multiplayer-technology-works/ |
| seq 기반 gap 감지 + full snapshot fallback | Discord Gateway | https://docs.discord.com/developers/events/gateway |
| Pub/Sub 신호 격하 + query 기반 상태 | Slack Flannel | https://slack.engineering/flannel-an-application-level-edge-cache-to-make-slack-scale/ |
| 이벤트 리플레이 진영과의 대비축 | 배민 SSE Last-Event-ID | https://techblog.woowahan.com/23199/ |
| single-flight/lease | Facebook memcache (NSDI '13) | https://www.usenix.org/conference/nsdi13/technical-sessions/presentation/nishtala |
| in-flight Promise 공유 | Instagram Thundering Herds & Promises | https://instagram-engineering.com/thundering-herds-promises-82191c8af57d |
| 국내 single-flight 라이브러리 | LINE req-shield (+55% TPS) | https://techblog.lycorp.co.jp/ko/req-saver-for-thundering-herd-problem-in-cache |
| 캐시 쇄도 국내 표준 레퍼런스 | 토스 캐시 문제 해결 가이드 | https://toss.tech/article/cache-traffic-tip |
| 분산 락 vs PER vs 백그라운드 갱신 비교 | 올리브영 (1.74s→5ms) | https://oliveyoung.tech/2025-08-04/gift-renewal-2/ |
| sorted set + Lua 원자 pop | Sidekiq scheduled.rb | https://github.com/sidekiq/sidekiq/blob/main/lib/sidekiq/scheduled.rb |
| durable 스케줄러 + 원자 획득 | Airbnb Dynein (±10초 SLA) | https://medium.com/airbnb-engineering/dynein-building-a-distributed-delayed-job-queueing-system-93ab10f05f99 |
| at-least-once + 동시 1개 보장 스펙 | Dropbox ATF | https://dropbox.tech/infrastructure/asynchronous-task-scheduling-at-dropbox |
| 국내 폴링 기반 지연 실행 | 카카오페이 지연이체 (if kakao 2024) | https://tech.kakaopay.com/post/ifkakao2024-delayed-transfer/ |
| Pub/Sub 유실 → List 영속으로 전환 | 올리브영 쿠폰 발급 | https://oliveyoung.tech/2023-08-07/async-process-of-coupon-issuance-using-redis/ |

---

## 6. 금지 표현 (debate report 확정 + 리서치 보강)

- Pub/Sub 메시지 순서 보장 / 메시지 유실 방지 / 강한 정합성 보장
- exactly-once **delivery** 구현 (→ "exactly-once 실행 효과"로)
- 모든 클라이언트 stale 완전 해결 (클라 stale drop 미구현)
- production 규모 수치 주장 (현재 전부 로컬 멀티 WAS 환경 — 측정 환경 병기)
- Lua 원자화 주장 (현재 코드는 Redisson RLock. 스케줄러 구현 시 소비 Lua만 별도 주장)
- 동시접속 N명 단독 강조

---

## 7. 실행 순서 (임팩트/비용 순)

1. **멀티 WAS 부하테스트 + Grafana 캡처** (Docker 기동 필요) → thundering herd bullet 수치 확정. 비용 최소, 임팩트 최대.
2. 클라이언트 stale drop 구현 → 주제 1을 "서버+클라 양쪽 검증"으로 승격 (선택).
3. **Sprint 7 스케줄러 구현** (Sorted Set + 폴러 + 원자 소비 + 중복/유실 테스트 + 지연 p99 메트릭) → 세 번째 bullet 확보. scenario.md의 Lua 전제와 현 코드(Redisson) 괴리는 "소비 연산만 Lua, 상태 변경은 락" 으로 분리 서술.
