# Sprint 1 Feedback

## 상태
**DOC_ONLY** — 설계 문서만 남기고 실제 인프라 구축/재현은 skip.

## Skip 결정 배경

사용자 지적:
> "게임 내용 데이터를 WAS 메모리에 올리는 것 자체가 일반적이지 않고 부작용 있지 않음? 굳이 이 스텝을 검증해봐야 아나?"

WAS 메모리 저장의 분산 환경 실패는 **업계 상식적 안티패턴**. Nginx + 2 WAS + `split-brain.yml` 신규 시나리오까지 구축해서 재현해도 "놀랍지 않은 결과"만 얻음. 시간 투자 대비 통찰 부족.

결론: 설계 문서(`01_architect_design.md`)로 **논증만 남기고** 실제 Sprint 3 구현 시 motivation으로 활용.

## 남긴 결과물
- `01_architect_design.md` — Split-Brain 재현 설계 (WAS 2대 + sticky 쿠키 기반)

## 인사이트 파일로 흡수
토론 중 얻은 인사이트는 `_workspace/insights/01_why_redis_ssot.md` 로 별도 저장.

## 예상 밖의 발견
- Sprint 1-2를 모두 skip하기로 결정한 직접적 계기는 Sprint 1 설계 문서 작성 후 **"이 프로젝트에 이미 Redis Pub/Sub/Stream/Lock이 전부 있었고 걷어냈다"**는 사실 재인식. archive/pre-cs-troubleshoot 브랜치의 12가지 패치가 바로 SSOT 부재의 현장 증거였음 → `02_actual_problems_in_project.md`로 정리.
- 재현 기반 학습보다 **"이미 이 프로젝트에서 겪었던 문제들을 다시 보는 것"** 이 동기부여로 더 강력.

## Sprint 3 인입 조건
다음 스프린트는 `_workspace/insights/01_why_redis_ssot.md` + `02_actual_problems_in_project.md`를 motivation 섹션 근거로 사용.
