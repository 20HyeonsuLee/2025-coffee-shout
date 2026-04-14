# Sprint 2 Feedback

## 상태
**DOC_ONLY** — Sprint 1과 동일 사유로 실제 재현 skip, 설계 문서만 유지.

## Skip 결정 배경

Sprint 2 주제(Sticky Session에서 방 전멸)는 Sprint 1의 "WAS 메모리 상태 저장" 한계가 시간이 지나면서 드러나는 파생 시나리오. Sprint 1 skip과 같은 논리가 적용됨:
- sticky의 실패 모드(장애/쿠키 유실/스케일 조정/부하 편중)는 알려진 안티패턴
- 구조적 논증으로 "왜 sticky를 폐기해야 하는가"를 확정하는 것으로 충분
- 재현 인프라(장애 주입, 쿠키 조작, 스케일 조정) 구축 비용이 학습 가치 대비 과도

## 남긴 결과물
- `01_sticky_session_limits.md` — 4가지 실패 모드(A~D) 분석 + sticky 폐기 결론

## 핵심 결론 (Sprint 3 전제)

> **sticky는 "분산 문제 해결"이 아니라 "단일 WAS인 척 가장"하는 것.**
> 라우팅 층이 아닌 **저장 층**을 분산 공유로 바꿔야 한다.

→ Sprint 3에서 stateless WAS + Redis SSOT + Pub/Sub으로 전환.

## 예상 밖의 발견
- 4개 실패 모드 정리를 하며 Sprint 전체 지도에서 Sprint 2가 단순 과도기가 아니라 **"라우팅 층 해결책 전부의 한계"** 를 단정짓는 위치임을 재확인. Sprint 3 Redis SSOT의 당위성이 이 문서로 확정됨.

## Sprint 3 인입 조건 (반복)
motivation 섹션 근거:
- `01_why_redis_ssot.md` (Stream/MySQL/Redis 비교)
- `02_actual_problems_in_project.md` (프로젝트 실제 사례 12건)
- `sprint_2/01_sticky_session_limits.md` (라우팅 층 한계 확정)
