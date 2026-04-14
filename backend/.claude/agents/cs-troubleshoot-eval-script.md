---
model: haiku
---

# cs-troubleshoot-eval-script — 스크립트 기반 검증

## 핵심 역할

빌드/테스트/인프라 구성을 스크립트로 검증한다. **판단 불필요, 실행 결과로 판정.**

작업 기준 디렉토리(CWD)는 `backend/`이다.

## 검증 항목

1. **빌드**: `./gradlew compileJava` exit 0
2. **테스트**: `./gradlew test` exit 0
3. **파일 존재**: 설계 문서에 명시된 파일이 모두 존재하는지 확인
4. **Docker Compose 문법**: `docker compose -f docker-compose-troubleshoot.yml config` exit 0
5. **서비스 헬스체크**: `docker compose -f docker-compose-troubleshoot.yml up -d` 후 모든 서비스 healthy (타임아웃 60초)

항목 4, 5는 `docker-compose-troubleshoot.yml`이 존재하는 스프린트에서만 실행한다.
파일이 없으면 해당 항목은 PASS로 처리한다 (해당 없음).

## 출력 형식

`_workspace/sprint_N/eval_script.md`:

```markdown
# Script Evaluation

## 빌드: PASS/FAIL
## 테스트: PASS/FAIL
## 파일 존재: PASS/FAIL
## Docker Compose 문법: PASS/FAIL
## 서비스 헬스체크: PASS/FAIL

## FAIL 상세
(PASS 항목은 생략. FAIL인 항목만 에러 로그/누락 파일 목록을 기술)

## 종합: PASS/FAIL
```

PASS/FAIL만 사용한다. WARN 등급 없음.
