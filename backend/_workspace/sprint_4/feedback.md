# Sprint 4 Feedback

작성일: 2026-04-29

## 잘 된 점

- 기존 Sprint 4 설계의 큰 주제인 "Thundering Herd"를 현재 구현된 `roomVersion` 흐름에 맞게 좁혔다.
- 단순 read cache가 아니라 recovery path 자체의 부하 증폭을 다뤄 포트폴리오 난도가 올라갔다.
- `SnapshotResyncCoordinator`로 orchestration을 분리해 단위 테스트가 가능해졌다.
- coalescing 중 더 높은 version이 들어오는 경우를 별도 테스트해 "read 절약 때문에 최신 상태를 놓치는" 반박을 막았다.
- metric을 먼저 설계해 Grafana에서 포폴 evidence로 이어질 길을 열었다.

## 아쉬운 점

- Docker daemon이 꺼져 있어 Testcontainers 기반 동시성 회귀와 멀티 WAS 부하테스트를 실행하지 못했다.
- 현재 수치는 아직 "unit verified + dashboard wired" 단계다. 포트폴리오 수치 claim으로 쓰려면 실제 부하 캡처가 필요하다.
- `SnapshotResyncPolicy` 값은 코드 상수다. 운영 튜닝까지 주장하려면 configuration property로 분리하는 편이 낫다.
- reconnect/subscription 초기 snapshot과 client-visible stale duration은 아직 구현 범위 밖이다.

## 다음 루프

1. Docker daemon 켜고 `DistributedLockConcurrencyTest` 재실행.
2. 기존 ready storm 부하테스트 재실행.
3. Grafana time range 안에 새 metric spike가 남도록 renderer screenshot 재캡처.
4. same-room gap herd를 의도적으로 만드는 test publisher 또는 JUnit fixture 추가.
5. `room_snapshot_read_total / pubsub_message_gap_detected_total` 값으로 full sync amplification claim 확정.
6. 측정 수치를 `portfolio_report.ko.md`와 이력서 bullet에 반영.

## 금지 Claim

- "Redis hot key 문제가 해결됐다"라고 단정하지 않는다.
- "reconnect storm p99를 낮췄다"라고 쓰지 않는다.
- "client stale duration을 보장했다"라고 쓰지 않는다.
- "Pub/Sub 유실을 모두 복구한다"라고 쓰지 않는다.

현 상태의 정확한 표현은 다음이다.

> 서버 gap recovery 경로에 snapshot read herd 방어와 관측 지표를 추가했고, 단위 테스트로 coalescing/cooldown/retry 동작을 검증했다. 실제 멀티 WAS 부하 수치는 Docker 기반 evidence run 이후 확정한다.
