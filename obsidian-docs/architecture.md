# Architecture

## System Overview

TabletopHelper is a standard three-tier web application: a React SPA frontend, a Spring Boot backend, and a PostgreSQL database. Real-time combat communication uses WebSockets (STOMP over SockJS) layered on top of the HTTP backend.

The system is designed for a single DM and up to 8 players — roughly 9 concurrent users. This constraint eliminates the need for horizontal scaling, message queues, load balancers, or Redis pub/sub. A single Spring Boot instance on a modest VPS handles all traffic trivially.

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                        CLIENTS                              │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐   │
│  │ DM       │  │ Player 1 │  │ Player 2 │  │ Player N │   │
│  │ (Laptop) │  │ (Phone)  │  │ (Phone)  │  │ (Phone)  │   │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘   │
│       │              │              │              │         │
│       └──────────────┴──────────────┴──────────────┘         │
│                          │                                   │
│              HTTP (REST) + WebSocket (STOMP)                 │
└──────────────────────────┬───────────────────────────────────┘
                           │
              ┌────────────▼────────────┐
              │   Nginx (Production)    │
              │   Reverse Proxy + SSL   │
              │   / → Frontend (static) │
              │   /api → Backend:8080   │
              │   /ws → Backend:8080    │
              └────────────┬────────────┘
                           │
         ┌─────────────────▼─────────────────┐
         │        Spring Boot Backend        │
         │           (Port 8080)             │
         │                                   │
         │  ┌─────────────────────────────┐  │
         │  │      Security Layer         │  │
         │  │  JWT Filter → Auth Check    │  │
         │  └─────────────┬───────────────┘  │
         │                │                  │
         │  ┌─────────────▼───────────────┐  │
         │  │     REST Controllers        │  │
         │  │  /api/auth/**               │  │
         │  │  /api/campaigns/**          │  │
         │  │  /api/characters/**         │  │
         │  │  /api/monsters/**           │  │
         │  │  /api/encounters/**         │  │
         │  └─────────────┬───────────────┘  │
         │                │                  │
         │  ┌─────────────▼───────────────┐  │
         │  │   WebSocket Controllers     │  │
         │  │  STOMP /app/encounter/**     │  │
         │  │  Topic /topic/encounter/**   │  │
         │  └─────────────┬───────────────┘  │
         │                │                  │
         │  ┌─────────────▼───────────────┐  │
         │  │     Service Layer           │  │
         │  │  AuthService                │  │
         │  │  CampaignService            │  │
         │  │  CharacterService           │  │
         │  │  CharacterMapper            │  │
         │  │  CharacterJsonHelper        │  │
         │  │  CombatService (M5)         │  │
         │  │  EncounterService (M4)      │  │
         │  │  FeatEffectResolver         │  │
         │  └─────────────┬───────────────┘  │
         │                │                  │
         │  ┌─────────────▼───────────────┐  │
         │  │   Repository Layer (JPA)    │  │
         │  └─────────────┬───────────────┘  │
         │                │                  │
         └────────────────┼──────────────────┘
                          │
              ┌───────────▼───────────┐
              │    PostgreSQL 16      │
              │   (Docker Container)  │
              │                       │
              │  users                │
              │  campaigns            │
              │  campaign_members     │
              │  player_characters    │
              │  monsters       (M3)  │
              │  spells         (M3)  │
              │  conditions     (M3)  │
              │  items          (M3)  │
              │  encounters     (M4)  │
              │  encounter_     (M4)  │
              │    participants       │
              │  combat_logs    (M5)  │
              └───────────────────────┘
```

## Data Flow: REST (CRUD Operations)

```
Client                    Backend                   Database
  │                         │                         │
  │  POST /api/auth/login   │                         │
  │ ──────────────────────► │                         │
  │                         │  SELECT * FROM users    │
  │                         │ ──────────────────────► │
  │                         │ ◄────────────────────── │
  │                         │  Validate password      │
  │                         │  Generate JWT           │
  │  {accessToken, ...}     │                         │
  │ ◄────────────────────── │                         │
  │                         │                         │
  │  GET /api/campaigns     │                         │
  │  Auth: Bearer <JWT>     │                         │
  │ ──────────────────────► │                         │
  │                         │  Validate JWT           │
  │                         │  Query campaigns        │
  │                         │ ──────────────────────► │
  │  [{campaign}, ...]      │ ◄────────────────────── │
  │ ◄────────────────────── │                         │
```

## Data Flow: WebSocket (Real-Time Combat)

```
DM Client          Backend              Player Clients
  │                  │                      │
  │  STOMP CONNECT   │                      │
  │  (access token   │                      │
  │   in header)     │                      │
  │ ───────────────► │                      │
  │                  │  Check access token  │
  │  CONNECTED       │                      │
  │ ◄─────────────── │                      │
  │                  │                      │
  │  SUBSCRIBE       │    SUBSCRIBE         │
  │  /topic/enc/X/   │    /topic/enc/X/     │
  │  dm-state        │    state             │
  │ ───────────────► │ ◄─────────────────── │
  │                  │  Guard: DM only /    │
  │                  │  members only        │
  │                  │                      │
  │  REST POST       │                      │
  │  /api/enc/X/     │                      │
  │  combat/attack   │                      │
  │ ───────────────► │                      │
  │                  │  Roll dice           │
  │                  │  Resolve attack      │
  │                  │  Update DB           │
  │                  │                      │
  │  MESSAGE         │    MESSAGE           │
  │  DM View         │    Player View       │
  │ ◄─────────────── │ ──────────────────► │
```

Since R3 the DM subscribes to `dm-state` and players to `state`, and `EncounterSubscriptionGuard` refuses any other destination; before R3 both sides subscribed to `state` and received the same full state (D157). Combat actions are REST calls: the STOMP `SEND /app/enc/X/attack` this diagram once showed was never built. See [[websocket-protocol]].

## Key Architectural Principles

1. **Server is the single source of truth.** All game logic (dice rolls, attack resolution, HP changes) runs on the backend. The client sends action requests; the server validates, resolves, and broadcasts results. Never trust the client for game state.

2. **Full state broadcast.** After every combat action, the server broadcasts the complete encounter state, once per view: the DM View to the DM and the Player View to players (D004, D157). This is simpler than delta updates and perfectly adequate for 9 users. Each client replaces its local state wholesale from the authoritative server state.

3. **Stateless authentication.** JWT access tokens allow the server to authenticate any request without a database lookup. This works for both REST and WebSocket — the token is in the Authorization header for HTTP and in the CONNECT frame for STOMP. Since R3 access tokens last 15 minutes, and only refreshing one touches the database: the refresh token travels in an HttpOnly cookie and is checked against `refresh_tokens` (D156, [[auth-flow]]).

4. **JSON columns for nested data.** Fields like ability score proficiencies, spell slots, equipment, and monster actions are stored as PostgreSQL `jsonb` columns rather than normalized into separate tables. This avoids an explosion of join tables while keeping the core relationships (users → characters → campaigns) properly relational.

## Project Structure

```
tabletophelper/
├── backend/
│   ├── build.gradle.kts          # Dependencies and build config
│   ├── settings.gradle.kts
│   ├── src/main/java/com/tabletophelper/
│   │   ├── TabletopHelperApplication.java
│   │   ├── config/               # CORS, STOMP broker and /ws endpoint, GlobalExceptionHandler
│   │   ├── identity/             # Public API: User, UserRole, UserService (R3; User was in user/)
│   │   │   └── internal/         # Login, register, refresh, logout, JWT provider, refresh tokens,
│   │   │                         # UserRepository, SecurityConfig, JWT filter, STOMP CONNECT check
│   │   │                         # (R3; formerly auth/, user/ and the security classes in config/)
│   │   ├── campaign/             # Campaign + members CRUD, AccessPolicy (D155)
│   │   ├── character/            # Player character CRUD, levelling, feats, rests
│   │   ├── monster/              # (Milestone 3)
│   │   ├── reference/            # Spells, conditions, items (Milestone 3)
│   │   ├── encounter/            # Encounters and combat (there is no combat/ package),
│   │   │                         # broadcaster, DM/Player Views, subscription guard
│   │   ├── resourcepool/         # Resource pools on characters and participants (M24.5)
│   │   ├── rules/                # Plain-Java rules: dice, expressions, proficiency, spell slots (R3)
│   │   ├── shared/               # Domain exceptions, mapped to HTTP statuses (D155)
│   │   ├── maintenance/          # One-off data correction jobs (R1)
│   │   └── seeder/               # 5e.tools data import (Milestone 3)
│   └── src/main/resources/
│       ├── application.yml       # Shared config
│       ├── application-dev.yml   # Local dev (Postgres on localhost)
│       └── application-prod.yml  # Production (env var driven)
├── frontend/
│   ├── src/
│   │   ├── api/                  # Axios clients for each backend resource
│   │   ├── context/              # React Context (Auth, Encounter)
│   │   ├── hooks/                # Custom hooks (useAuth, useWebSocket)
│   │   ├── pages/                # Route-level page components
│   │   │   ├── dm/               # DM-specific pages
│   │   │   └── player/           # Player-specific pages
│   │   ├── components/           # Reusable UI components
│   │   ├── utils/                # Utility modules (featSpellParser, parseMarkup)
│   │   └── types/                # TypeScript interfaces
│   ├── vite.config.ts            # Dev server proxy + Tailwind plugin
│   └── package.json
├── docker-compose.yml            # PostgreSQL for local dev
└── obsidian-docs/                # This documentation
```

## Module Boundaries (D151)

The backend is a modular monolith: one deployable Spring Boot application whose modules have enforced boundaries. Each package directly below `com.tabletophelper` counts as a module and has a `package-info.java` describing it. R3 built the skeleton (D150): it moved packages without changing behaviour, and each later phase reshapes its own area.

- **`identity`** exposes only `User`, `UserRole` and `UserService`, whose `getUser(id)` throws `NotFoundException` for an unknown user. Everything else lives in `identity.internal`: `AuthController`, `AuthService`, `JwtTokenProvider`, `RefreshToken` and its repository, `UserRepository`, `SecurityConfig`, `JwtAuthenticationFilter`, `WebSocketAuthInterceptor` and `WebSocketAuthConfig`. `CampaignService` and `CharacterService` look users up through `UserService`. `User` stays public until other modules hold user IDs instead.
- **`rules`** holds the static calculators `SpellSlotCalculator`, `SpellSlotTables`, `ProficiencyBonus`, `DiceRoller` and `ExpressionEvaluator`, moved with their tests from `character`, `encounter` and `resourcepool`.
- **`shared`** holds the domain exceptions any module may throw: `NotFoundException`, `ForbiddenException`, `ConflictException` and `ValidationException` (D155).
- **`campaign`** holds `AccessPolicy`, which takes IDs rather than entities. `campaign` therefore uses only identity's public API and `shared`, and nothing from `character` or `encounter`, which call the policy (D155).
- **WebSocket interceptors are registered by the module that owns them:** identity's `WebSocketAuthConfig`, at highest precedence, checks CONNECT before encounter's `EncounterSubscriptionConfig` checks SUBSCRIBE and SEND frames. `EncounterWebSocketIT` asserts the order.
- The other packages (`config`, `monster`, `reference`, `resourcepool`, `maintenance`, `seeder`) count as modules for the test but haven't been reorganised yet, and D151's `content` module doesn't exist yet.

`ModuleBoundaryTest` (ArchUnit, `backend/src/test/java/com/tabletophelper/architecture/`, run by `./gradlew test`) checks the production classes, not the tests, against two rules:

1. No class outside a module may use that module's `internal` package.
2. Classes in `rules` may depend only on `java..`, `rules..` and `shared..`: no Spring, no JPA and no other module.

Both rules were checked against deliberate violations. Spring Modulith's `verify()` was not used because today's `character` ↔ `encounter` dependency cycle would fail it.
