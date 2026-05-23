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
| `handler/`           | `VoiceStreamHandler` — WebSocket binary frames  |

### NLP Order Parsing Pipeline (`OrderParserService`)

The core of the system. When a user utterance arrives, it resolves menus through these layers in order:

1. **Greedy exact match** — longest-first matching against menu names + synonyms
2. **Synonym lookup** — user-taught phrases from `MenuSynonym` table
3. **Pronoun resolution** — "이거/그거" via Redis context (`OrderContextService`, 10-min TTL)
4. **Semantic AI** — calls Python backend at `http://localhost:8000/recommend`; filters results with `score ≥ 0.5` AND `score ≥ (maxScore - 0.05)`
5. **Levenshtein fallback** — edit-distance nearest match

Quantity words ("하나", "두개", etc.) are resolved by `QuantityResolverService` using `QuantitySynonym` DB table, then regex `\d+`. Cancellation is detected by `CancelResolverService` using `CancelSynonym` table.

### Session Model

Everything is keyed by `sessionId` (from WebSocket session or `X-Session-ID` header):

- `cart:{sessionId}` — Redis Hash, 30-min TTL
- `order_context:{sessionId}` — last ordered menu ID, 10-min TTL

### Voice Flow

`/ws/voice` WebSocket receives raw audio (LINEAR16, 16kHz), streams to Google Cloud STT (`ko-KR`), and on final transcript calls `OrderParserService` then `CartService`. Credentials at `/home/kambook/google-key.json`.

### Learning Flow

`POST /api/learning` — user provides free-form text + `menuId`. `MenuLearningService` splits by spaces, identifies quantity token, stores the remainder as a new `MenuSynonym` (stripped of spaces), and immediately adds to cart.

### File Uploads

Menu images: UUID-prefixed filenames stored at `~/kiosk_uploads/menu/`. Served from `https://kemini-kiosk-api.duckdns.org/uploads/menu/{imageName}`.

## Key Entity Relationships

- `MenuCategory` 1→N `Menu` 1→N `MenuSynonym`
- `Menu` 1→1 `MenuStatistics`
- `PronounSynonym`, `QuantitySynonym`, `CancelSynonym` are standalone lookup tables (no FK to Menu)

## External Dependencies

| Service          | Address                 | Purpose                       |
| ---------------- | ----------------------- | ----------------------------- |
| MariaDB          | `localhost:3303`        | Primary DB (`keminikiosk`)    |
| Redis            | `localhost:6373`        | Cart + order context sessions |
| Python AI server | `http://localhost:8000` | Semantic menu recommendations |
| Google Cloud STT | GCP API                 | Korean voice recognition      |
