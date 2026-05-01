# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

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

| Package | Responsibility |
|---------|---------------|
| `controller/` | REST endpoints + WebSocket handler |
| `service/` | Business logic, NLP parsing, Redis operations |
| `domain/entity/` | JPA entities (7 tables) |
| `domain/repository/` | Spring Data JPA repositories |
| `dto/` | Request/response DTOs |
| `config/` | Redis, WebSocket, Web (CORS) config |
| `global/` | `ApiResponse` wrapper, global exception handler |
| `handler/` | `VoiceStreamHandler` — WebSocket binary frames |

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

| Service | Address | Purpose |
|---------|---------|---------|
| MariaDB | `localhost:3303` | Primary DB (`keminikiosk`) |
| Redis | `localhost:6373` | Cart + order context sessions |
| Python AI server | `http://localhost:8000` | Semantic menu recommendations |
| Google Cloud STT | GCP API | Korean voice recognition |
