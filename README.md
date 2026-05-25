# 2026 금오공대 창의설계프로젝트

## 자연어 맥락 이해 기반 대화형 키오스크 시스템 — 백엔드

한국어 음성·텍스트 입력을 받아 자연어 처리 파이프라인으로 메뉴를 인식하고, 세션 기반 장바구니를 관리하는 Spring Boot 백엔드입니다.

---

## 기술 스택

| 항목 | 내용 |
|------|------|
| 언어 / 프레임워크 | Java 21 + Spring Boot 4.0.3 |
| 데이터베이스 | MariaDB |
| 세션 / 캐시 | Redis (Lettuce) |
| 음성 인식 | Google Cloud Speech-to-Text (ko-KR) |
| AI 추천 | Python 의미 검색 서버 (FastAPI 등) |
| 립리딩 | Python 비전 서버 (MediaPipe 기반) |
| 빌드 도구 | Gradle |

---

## 실행 방법

### 사전 요구사항

| 서비스 | 주소 | 용도 |
|--------|------|------|
| MariaDB | `localhost:3303` | 메인 DB (`keminikiosk`) |
| Redis | `localhost:6373` | 장바구니 · 주문 컨텍스트 세션 |
| Python AI 서버 | `http://localhost:8000` | 의미 기반 메뉴 추천 |
| Python 비전 서버 | `app.vision-server-url` (ngrok) | 립리딩 분석 |
| Google Cloud 인증키 | `/home/kambook/google-key.json` | 음성 인식 |

### 빌드 및 실행

```bash
# 빌드
./gradlew build

# 개발 서버 실행 (포트 8727)
./gradlew bootRun

# 테스트
./gradlew test

# 클린 빌드
./gradlew clean build
```

---

## 주요 기능

### 1. 하이브리드 NLP 주문 파싱

사용자 발화를 다음 5단계 레이어로 순차 분석합니다.

```
입력: "아아 두 개랑 라떼 하나 줘"
  │
  ▼
1. Greedy 정확 매칭   — 메뉴명·동의어를 길이 내림차순으로 탐색
2. 동의어 매칭        — 사용자가 학습시킨 별칭 (예: "아아" → 아이스 아메리카노)
3. 대명사 해석        — "이거/그거" → Redis에 저장된 마지막 주문 메뉴
4. AI 의미 검색       — Python 서버 호출, score ≥ 0.5 & ≥ (maxScore - 0.05) 필터
5. Levenshtein 폴백  — 편집 거리 기반 근사 매칭 (score > 0.3)
```

### 2. 음성 주문 + 립리딩 융합

`/ws/voice` WebSocket으로 오디오를 수신합니다.

- 클라이언트: 마이크 오디오를 16kHz PCM 바이너리 프레임으로 전송
- 서버: Google Cloud STT 스트리밍 인식 (`singleUtterance=true`) → `isFinal=true` 수신 시 주문 파싱 후 아래 3경로 중 하나로 분기

**NLP 결과 × STT confidence 분기 로직:**

| NLP 결과 | 시노님 매칭 | confidence | 처리 경로 |
|---|---|---|---|
| 실제 주문 있음 | O | 무관 | **확인 모달** — `SYSTEM:CONFIRM_ORDER:{json}` |
| 실제 주문 있음 | X | ≥ 0.6 | **즉시 처리** — 장바구니 추가 + `SYSTEM:PROCESS_ORDERS:{json}` |
| 실제 주문 있음 | X | < 0.6 | **교차 검증** — 보류 주문 저장, 카메라 프레임 전달, `SYSTEM:LIPREADING_ANALYZING` |
| NLP 실패 / Levenshtein만 | — | 무관 | **AI 추천 또는 립리딩** |

> 시노님 매칭: `MenuSynonym` 테이블의 별칭으로 매칭된 경우. 직접 메뉴명 매칭은 즉시 처리.

`/ws/lipreading` WebSocket으로 카메라 프레임을 수신합니다.

- 카메라가 켜져 있는 동안 상시 연결 유지 (15fps, 최대 **105프레임 / 7초** 버퍼, 마지막 1초는 자동 제거)
- 비전 서버 콜백(`POST /api/lipreading/result`) 수신 시 립리딩 모음과 STT 결과 융합
- 융합 결과에 따른 WebSocket 메시지:

| 메시지 | 의미 |
|--------|------|
| `SYSTEM:CONFIRM_ORDER:[{...}]` | 시노님 매칭 → 프론트에서 "맞아요/아니요" 확인 모달 |
| `SYSTEM:PROCESS_ORDERS:{json}` | 직접 매칭 고신뢰도 → 자동 장바구니 추가 |
| `SYSTEM:AI_CANDIDATES:[{...}]` | AI 추천 결과 직접 전송 → 프론트 선택 모달 |
| `SYSTEM:LIPREADING_ANALYZING` | 립리딩 분석 시작 알림 |
| `SYSTEM:LIPREADING_MATCH:{id}:{name}:{score}` | 교차 검증 성공 → 자동 장바구니 추가 |
| `SYSTEM:LIPREADING_CANDIDATES:[{...},...]` | 립리딩 추천 → 프론트에서 사용자 확인 모달 |
| `SYSTEM:LIPREADING_FAILED` | 립리딩 유사도 기준 미달 |

### 3. 실시간 학습

인식되지 않은 표현을 즉시 동의어로 등록합니다.

```
POST /api/learning  { "text": "달콤한 빵 하나", "menuId": 5 }
  → "달콤한빵" 동의어 DB 저장
  → 수량 1개 장바구니 즉시 추가
```

### 4. 세션 기반 장바구니

모든 데이터는 `sessionId`로 Redis에 저장됩니다.

| Redis 키 | 구조 | TTL |
|----------|------|-----|
| `cart:{sessionId}` | Hash (menuId → CartItem JSON) | 30분 |
| `order_context:{sessionId}` | String (menuId) | 10분 |

---

## API 엔드포인트

### 메뉴

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `POST` | `/api/menu` | 메뉴 생성 (multipart/form-data) |
| `GET` | `/api/menu` | 메뉴 목록 (`?categoryId=` 옵션) |
| `PUT` | `/api/menu/{id}` | 메뉴 수정 |
| `DELETE` | `/api/menu/{id}` | 메뉴 삭제 |

### 카테고리

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `POST` | `/api/category` | 카테고리 생성 |
| `GET` | `/api/category` | 카테고리 목록 |
| `DELETE` | `/api/category/{id}` | 카테고리 삭제 |

### 장바구니

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `POST` | `/api/cart/{sessionId}` | 메뉴 추가 |
| `GET` | `/api/cart/{sessionId}` | 장바구니 조회 |
| `PATCH` | `/api/cart/{sessionId}/{menuId}` | 수량 조절 (`?delta=±1`) |
| `DELETE` | `/api/cart/{sessionId}` | 장바구니 전체 비우기 |
| `DELETE` | `/api/cart/{sessionId}/{menuId}` | 특정 메뉴 삭제 |

### 기타

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `POST` | `/api/learning` | 동의어 학습 + 장바구니 추가 (헤더: `X-Session-ID`) |
| `POST` | `/api/ai/recommend` | AI 메뉴 추천 |
| `GET` | `/api/statistics/top3` | 주문 TOP3 (`?categoryName=` 옵션) |
| `POST` | `/api/statistics/order` | 주문 통계 기록 |
| `POST` | `/api/lipreading/result` | 비전 서버 립리딩 콜백 수신 |
| `WS` | `/ws/voice` | 음성 스트리밍 |
| `WS` | `/ws/lipreading` | 카메라 프레임 스트리밍 (립리딩용) |

---

## 데이터 구조

```
MenuCategory
  └── Menu (1:N)
        ├── MenuSynonym   — 학습된 별칭
        └── MenuStatistics — 주문 횟수

PronounSynonym   — 이거, 그거, 저거 ...
QuantitySynonym  — 하나=1, 두개=2, 세잔=3 ...
CancelSynonym    — 취소, 빼줘, 전부 ...
```

---

## 설정 (`application.yml` 주요 항목)

```yaml
server.port: 8727

spring:
  datasource.url: jdbc:mariadb://localhost:3303/keminikiosk
  data.redis:
    host: localhost
    port: 6373

app:
  base-url: https://kemini-kiosk-api.duckdns.org
  vision-server-url: https://<ngrok-주소>  # 비전 서버 ngrok URL
```
