# Mission Contract: Room Versioned Pub/Sub Recovery

## 1. Mission Summary

- 목표: Redis SSOT + Pub/Sub WebSocket 전파 구조에 `roomVersion` 기반 stale drop/gap detection/snapshot recovery 기반을 구현하고, 동시성/부하 테스트 evidence를 남긴다.
- 사용자가 기대하는 최종 상태: 이력서/포트폴리오에 방어 가능한 문제 정의, 구현 근거, 테스트/메트릭 산출물이 남는다.
- 이 mission이 해결하려는 실제 문제: 방 단위 write invariant는 분산락으로 보호됐지만, Pub/Sub의 중복/역순/유실/reconnect 상황에서 클라이언트 관찰 상태가 stale해질 수 있다.
- 예상 작업 유형: mixed

## 2. Non-Goals

- NG-1: Lua, Kafka, Redis Stream, actor model은 구현하지 않는다.
- NG-2: strong consistency, Pub/Sub 유실 방지, 메시지 순서 보장을 주장하지 않는다.
- NG-3: production/staging 부하 테스트나 외부 시스템 부하는 실행하지 않는다.

## 3. Completion Criteria

### AC-1. Redis roomVersion 기반 envelope

- 사용자 가치: 상태 변경 순서를 서버/클라이언트가 검증할 수 있다.
- 완료 상태: Redis `room:{joinCode}:version`이 상태 변경 성공마다 증가하고, Pub/Sub envelope에 `version`, `eventId`, `originInstanceId`, `eventType`, `joinCode`, `publishedAt`이 포함된다.
- 검증 방법: 단위/통합 테스트와 `compileJava`.
- PASS 조건: create/join/ready/remove/positions publish 경로가 versioned envelope를 사용한다.
- FAIL 조건: version 없는 publish 경로가 남아 있다.
- FAIL 시 다음 행동: repository publish 경로를 재검토한다.
- Evidence 경로: `_workspace/mission_room_version_consistency/evidence/`

### AC-2. Subscriber stale drop/gap detection

- 사용자 가치: 중복/역순 메시지로 인한 불필요 broadcast를 줄이고 gap을 감지해 snapshot recovery로 전환한다.
- 완료 상태: subscriber가 room별 `lastSeenVersion`을 관리하고, `version <= lastSeenVersion`은 drop, `version > lastSeenVersion + 1`은 Redis snapshot을 읽어 full-state broadcast한다.
- 검증 방법: PubSubSubscriber 단위 테스트 또는 통합 테스트.
- PASS 조건: stale drop, in-order broadcast, gap recovery 케이스가 검증된다.
- FAIL 조건: gap/stale가 구분되지 않는다.
- FAIL 시 다음 행동: subscriber state machine을 분리해 테스트 가능하게 만든다.
- Evidence 경로: `_workspace/mission_room_version_consistency/evidence/`

### AC-3. 동시성 baseline 유지

- 사용자 가치: versioned Pub/Sub 도입이 기존 room write invariant를 깨지 않는다.
- 완료 상태: 기존 joinCode/정원/중복/동시 제거 테스트가 통과한다.
- 검증 방법: `./gradlew test --tests coffeeshout.concurrency.DistributedLockConcurrencyTest --no-configuration-cache`
- PASS 조건: 타깃 테스트 PASS.
- FAIL 조건: 기존 동시성 테스트 FAIL.
- FAIL 시 다음 행동: Redis key/version 변경이 기존 상태 저장과 충돌하는지 확인한다.
- Evidence 경로: `_workspace/mission_room_version_consistency/evidence/`

### AC-4. Metric evidence 기반 마련

- 사용자 가치: 이후 이력서/포트폴리오에서 수치 기반 claim을 만들 수 있다.
- 완료 상태: 최소한 Pub/Sub propagation, stale drop, gap detected, snapshot resync count/latency metric이 기록된다.
- 검증 방법: 코드 검색, 테스트, actuator metric 확인 가능성 검토.
- PASS 조건: Micrometer metric 이름과 증가 지점이 명확하다.
- FAIL 조건: 수집할 metric이 코드에 없다.
- FAIL 시 다음 행동: metric service를 확장한다.
- Evidence 경로: `_workspace/mission_room_version_consistency/evidence/`

### AC-5. Load/concurrency test artifact

- 사용자 가치: 나중에 Grafana 캡처와 포트폴리오 수치를 뽑을 실행 시나리오가 있다.
- 완료 상태: 로컬 안전 범위에서 실행 가능한 부하/동시성 테스트 계획 또는 artifact가 `_workspace`에 저장된다.
- 검증 방법: artifact 파일 확인 및 가능하면 dry-run/targeted test.
- PASS 조건: 테스트 목적, 실행 명령, 수집 metric, Grafana panel 후보가 문서화된다.
- FAIL 조건: 수치 수집 경로가 없다.
- FAIL 시 다음 행동: `metric-evidence-capture` 계획을 보강한다.
- Evidence 경로: `_workspace/mission_room_version_consistency/evidence/`

### AC-6. Snapshot resync herd 완화

- 사용자 가치: gap/reconnect/WAS restart 때 Redis snapshot read가 한 방에 몰려 hot key가 되는 위험을 줄인다.
- 완료 상태: 서버 subscriber gap recovery에 room 단위 single-flight/coalescing, version-aware cooldown, jitter/backoff, bounded retry가 적용된다.
- 검증 방법: `SnapshotResyncCoordinatorTest`와 Prometheus metric 확인.
- PASS 조건: 같은 방/같은 version 동시 resync는 snapshot read 1회로 합쳐지고, 더 높은 version은 cooldown/coalescing 때문에 누락되지 않으며, retry/fail metric이 존재한다.
- FAIL 조건: gap 1건마다 무조건 별도 Redis snapshot read를 수행하거나, 최신 version 요청을 cooldown으로 잘못 생략한다.
- FAIL 시 다음 행동: coordinator의 version coverage 조건과 in-flight lifecycle을 재검토한다.
- Evidence 경로: `_workspace/mission_room_version_consistency/evidence/`

## 4. Quality Gates

- Build gate:
  - command: `./gradlew compileJava --no-configuration-cache`
  - PASS: BUILD SUCCESSFUL
  - FAIL action: 컴파일 오류 수정

- Test gate:
  - command: `./gradlew test --tests coffeeshout.concurrency.DistributedLockConcurrencyTest --tests coffeeshout.global.messaging.PubSubSubscriberVersionTest --tests coffeeshout.global.messaging.SnapshotResyncCoordinatorTest --no-configuration-cache`
  - PASS: BUILD SUCCESSFUL
  - FAIL action: 동시성 invariant 회귀 수정

- Documentation gate:
  - required docs: mission report, metric evidence plan
  - PASS: 산출물 경로와 next action 명시
  - FAIL action: `_workspace` 문서 보강

## 5. Autonomy Rules

### GREEN: 묻지 않고 진행

- 로컬 코드 수정, 테스트 추가, `_workspace` 문서 작성.
- 로컬 Gradle compile/test 실행.
- Redis/Grafana metric plan 작성.

### YELLOW: decision_log에 남기고 진행

- 내부 DTO/record 필드 추가.
- metric 이름 추가.
- 테스트 가능성을 위한 작은 package-private helper 추가.

### RED: 멈추고 사용자 승인 요청

- public API breaking change.
- DB migration 또는 데이터 삭제.
- production/staging/외부 시스템 부하 테스트.
- secret/Grafana credential 접근.
- mission 목표 변경.

## 6. Loop Policy

- 최대 반복 횟수: 3
- 같은 실패 반복 허용 횟수: 2
- 각 iteration 산출물 경로: `_workspace/mission_room_version_consistency/iter_<N>/`
- iteration 종료마다 기록할 것: 변경 요약, 검증 결과, 남은 실패, 다음 행동.

## 7. Stop Conditions

- 모든 Completion Criteria PASS.
- RED 조건 발생.
- max iteration 초과.
- 테스트/빌드 환경 자체가 깨져 검증 불가.

## 8. Final Report Requirements

- 완료된 기준 목록.
- 실패 또는 보류된 기준.
- 실제 실행한 명령.
- evidence 경로.
- 변경 파일 요약.
- 남은 리스크.
- 다음 mission 후보.

## 9. Readiness Verdict

- 판정: READY_TO_RUN
- 이유: 현재 Redis SSOT/PubSub/분산락 baseline이 존재하고, roomVersion은 Redis key + envelope + subscriber state machine으로 국소 구현 가능하다.
- 사용자에게 물어볼 질문: 없음. 로컬 구현/테스트 범위에서 진행한다.
