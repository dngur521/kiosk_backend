# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:

- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:

- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:

- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:

- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:

```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

---

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.

## Project Overview

Korean-language conversational kiosk backend for Kumoh National Institute of Technology (2026 creative design project). Accepts natural language (voice/text) orders in Korean, parses them with a hybrid NLP pipeline, and manages a session-based cart.

## Build & Run Commands

```bash
# Build
./gradlew build

# Run (dev)
./gradlew bootRun

# Run tests
./gradlew test

# Clean build
./gradlew clean build
```

Server runs on port **8727**. Requires MariaDB on `localhost:3303` and Redis on `localhost:6373`.

## Architecture

### Package Layout (`com.kemini.kiosk_backend/`)

| Package              | Responsibility                                  |
| -------------------- | ----------------------------------------------- |
| `controller/`        | REST endpoints + WebSocket handler              |
| `service/`           | Business logic, NLP parsing, Redis operations   |
| `domain/entity/`     | JPA entities (7 tables)                         |
| `domain/repository/` | Spring Data JPA repositories                    |
| `dto/`               | Request/response DTOs                           |
| `config/`            | Redis, WebSocket, Web (CORS) config             |
| `global/`            | `ApiResponse` wrapper, global exception handler |
| `handler/`           | `VoiceStreamHandler`, `LipReadingFrameHandler` — WebSocket binary frames |

### NLP Order Parsing Pipeline (`OrderParserService`)

The core of the system. When a user utterance arrives, it resolves menus through these layers in order:

1. **Greedy exact match** — longest-first matching against menu names + synonyms
2. **Synonym lookup** — user-taught phrases from `MenuSynonym` table
3. **Pronoun resolution** — "이거/그거" via Redis context (`OrderContextService`, 10-min TTL)
4. **Semantic AI** — calls Python backend at `http://localhost:8000/recommend`; Python server applies `min_absolute_threshold = 0.78` before returning; Spring Boot additionally filters with `score ≥ 0.5` AND `score ≥ (maxScore - 0.05)`
5. **Levenshtein fallback** — edit-distance nearest match

Quantity words ("하나", "두개", etc.) are resolved by `QuantityResolverService` using `QuantitySynonym` DB table, then regex `\d+`. Cancellation is detected by `CancelResolverService` using `CancelSynonym` table.

### Session Model

Everything is keyed by `sessionId` (from WebSocket session or `X-Session-ID` header):

- `cart:{sessionId}` — Redis Hash, 30-min TTL
- `order_context:{sessionId}` — last ordered menu ID, 10-min TTL

### Voice Flow

`/ws/voice` WebSocket receives raw audio (LINEAR16, 16kHz), streams to Google Cloud STT (`ko-KR`, `singleUtterance=true`), and on final transcript calls `OrderParserService` then routes based on NLP result + confidence. Credentials at `/home/kambook/google-key.json`.

> **현재 상태 (2026-05-25): 립리딩 비활성화.** 경로 3(저신뢰도 교차검증)과 경로 4의 립리딩 폴백이 주석처리됨.
> - 활성 경로: `CONFIRM_ORDER`(시노님) · `PROCESS_ORDERS`(고신뢰도) · `AI_CANDIDATES`(AI 추천) · `POPULAR_MENUS`(NLP·AI 전부 실패)
> - 경로 3(저신뢰도)은 현재 no-op — 아무 메시지도 전송 안 함
> - 복원 방법은 아래 "Lip-Reading Flow" 섹션 참고

**STT stream lifecycle:** Lazy restart — the stream is NOT proactively restarted after a final result or error. `handleBinaryMessage` starts a new stream only when the next audio chunk arrives and no stream exists. This prevents the `OUT_OF_RANGE: Audio Timeout` infinite loop caused by keeping an empty stream alive. An `AtomicReference<ClientStream>` inside `startSttStream` detects stale `onError`/`onComplete` callbacks (fired by old streams after a new one is already running) and silently drops them.

### Lip-Reading Flow

> **현재 비활성화 상태.** 아래 복원 체크리스트로 언제든지 다시 켤 수 있음.

#### 복원 체크리스트 (grep `// 립리딩 비활성화`)

| 파일 | 할 일 |
|------|-------|
| `handler/LipReadingFrameHandler.java` | `// @Component` → `@Component` |
| `controller/LipReadingController.java` | `// @RestController`, `// @RequestMapping` 해제 |
| `service/FrameBufferService.java` | `// @Service` → `@Service` |
| `service/LipReadingService.java` | `// @Service` → `@Service` |
| `service/LipReadingSessionContext.java` | `// @Component` → `@Component` |
| `config/WebSocketConfig.java` | `LipReadingFrameHandler` import·필드·`/ws/lipreading` 등록 해제 |
| `handler/VoiceStreamHandler.java` | imports·필드·`store()`·교차검증 경로·AI폴백 경로·`sendFramesToPython` 메서드 해제 |

---

Camera frames from React are buffered in Spring Boot, then forwarded to the vision server depending on STT confidence and NLP outcome.

1. React connects to `/ws/lipreading` (persistent, stays open while camera is on)
2. `LipReadingFrameHandler` stores binary frames in `FrameBufferService` (circular buffer, max **105 frames = 15fps × 7s**; `drainFrames()` drops the last 15 frames / 1s to remove post-utterance silence)
3. On STT `isFinal=true`, `VoiceStreamHandler` evaluates two dimensions:

   **`hasRealOrder`** — true when NLP produced at least one actionable result (non-`isUnknown`, with a real menu+quantity or a cancel action). Synonym matches (`isLearnedMatch=true`) count toward `hasRealOrder`.

   **`hasSynonymMatch`** — true when any matched order came from the `MenuSynonym` table (as opposed to a direct menu name match).

   | `hasRealOrder` | `hasSynonymMatch` | confidence | Path | 현재 상태 |
   |---|---|---|---|---|
   | `true` | `true` | any | **Confirm** — send `SYSTEM:CONFIRM_ORDER:{json}`; frontend shows "맞아요/아니요" modal | ✅ 활성 |
   | `true` | `false` | ≥ 0.6f | **Direct** — cart add immediately; send `SYSTEM:PROCESS_ORDERS:{json}` | ✅ 활성 |
   | `true` | `false` | < 0.6f | **Cross-validation** — `storePendingOrders`, drain buffer, POST `/stt` to vision server; send `SYSTEM:LIPREADING_ANALYZING` | 🚫 no-op (립리딩 비활성화) |
   | `false` | — | any | AI 추천 있으면 `SYSTEM:AI_CANDIDATES`; 없으면 `SYSTEM:POPULAR_MENUS` (인기 메뉴 TOP3, `OrderStatisticsService.getTop3Menus()`) | ✅ 활성 |

4. Vision server analyzes frames → `POST /api/lipreading/result` callback → `LipReadingService.processResult(lipVowels)`
5. `LipReadingService` dispatches based on whether pending orders exist:
   - **Cross-validation path** (`hasPendingOrders=true`): compare each pending menu's vowels against lip vowels via Levenshtein similarity. If `bestScore ≥ 0.5` → cart add + `SYSTEM:LIPREADING_MATCH:{id}:{name}:{score}`. If `bestOrder == null` (e.g. only Levenshtein orders pending, which are filtered out) → falls back to recommendation path. If `bestScore < 0.5` → `SYSTEM:LIPREADING_FAILED`.
   - **Recommendation path** (`hasPendingOrders=false`): scan all menus, compute vowel similarity, send TOP 3 as `SYSTEM:LIPREADING_CANDIDATES:[{"id":N,"name":"...","score":0.XX,"quantity":1},...]` for user to confirm.

Vision server URL configured via `app.vision-server-url` in `application.yml` — do not hardcode.

**TODO before production:** `VoiceStreamHandler.java` confidence threshold is `0.6f` for testing. Restore to `0.8f` before release.

### Learning Flow

`POST /api/learning` — user provides free-form text + `menuId`. `MenuLearningService` splits by spaces, identifies quantity token, stores the remainder as a new `MenuSynonym` (stripped of spaces), and immediately adds to cart.

### File Uploads

Menu images: UUID-prefixed filenames stored at `~/kiosk_uploads/menu/`. Served from `https://kemini-kiosk-api.duckdns.org/uploads/menu/{imageName}`.

The base URL is configured via `app.base-url` in `application.yml` and injected with `@Value("${app.base-url}")` — do not hardcode it in service/controller classes.

## Known Risks & Gotchas

### LipReadingSessionContext is not keyed by sessionId

`LipReadingSessionContext` is a single global Spring bean (`volatile` fields, no map). This is intentional — the kiosk assumes a single concurrent user. Two failure modes to be aware of:

1. **Rapid consecutive utterances** — if a second STT `isFinal` arrives while Python is still processing the first utterance's frames, the second `store()` call resets `pendingOrders=empty` and `lipReadingConsumed=false`. When the first Python callback arrives it finds empty `pendingOrders` and falls into recommendation mode instead of cross-validation. Unlikely in normal use (`singleUtterance=true` forces a pause between utterances) but reproducible with fast speech.

2. **Page refresh mid-flight** — a browser refresh creates a new WebSocket session (new sessionId). If Python is still processing frames from the old session, its callback fires on the new session's context. The `tryConsumeLipReading()` flag partially guards against this, but depending on timing it can consume the new session's first lip-reading slot. Fix if this becomes observable: key `LipReadingSessionContext` by sessionId instead of a single bean.

### `cleanup()` does not clear LipReadingSessionContext

`afterConnectionClosed` → `cleanup(sessionId)` tears down the STT stream and SpeechClient but leaves `LipReadingSessionContext.session` pointing at the now-closed WebSocket. `ws.isOpen()` checks in `LipReadingService` prevent actual send errors, but stale state persists until the next `store()` call. If this becomes an issue, add `lipReadingSessionContext.store(null, null, 0f)` (or a dedicated `clear()`) inside `cleanup()`.

## Key Entity Relationships

- `MenuCategory` 1→N `Menu` 1→N `MenuSynonym`
- `Menu` 1→1 `MenuStatistics`
- `PronounSynonym`, `QuantitySynonym`, `CancelSynonym` are standalone lookup tables (no FK to Menu)

## External Dependencies

| Service          | Address                 | Purpose                       |
| ---------------- | ----------------------- | ----------------------------- |
| MariaDB          | `localhost:3303`        | Primary DB (`keminikiosk`)    |
| Redis            | `localhost:6373`        | Cart + order context sessions |
| Python AI server    | `http://localhost:8000`    | Semantic menu recommendations |
| Python vision server | `app.vision-server-url` (ngrok) | Lip-reading analysis |
| Google Cloud STT | GCP API                    | Korean voice recognition      |

## Commit Message Convention

Format: `{emoji} {type}: {description (한국어, 한 줄)`

| Type | Emoji | 용도 |
|------|-------|------|
| `feat` | ✨ | 새 기능 |
| `fix` | 🐛 | 버그 수정 |
| `docs` | 📝 | 문서 작성·수정 |
| `refactor` | ♻️ | 기능 변경 없는 코드 개선 |
| `chore` | 🔧 | 빌드·설정·의존성 변경 |

Example: `✨ feat: 음성 주문 취소 기능 추가`
