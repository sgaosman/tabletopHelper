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
         │  │  Combat services (R5)       │  │
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

2. **Full state broadcast.** After every combat action, the server broadcasts the complete encounter state, once per view: the DM View to the DM and the Player View to players (D004, D157). Since R5 this happens only after the command's transaction commits, so a command that rolls back publishes nothing (D160). This is simpler than delta updates and perfectly adequate for 9 users. Each client replaces its local state wholesale from the authoritative server state.

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
│   │   ├── encounter/            # Encounters and combat (there is no top-level combat/ package),
│   │   │   │                     # broadcaster, DM/Player Views, subscription guard
│   │   │   ├── internal/         # The combat services, EffectApplier, TurnProcessor (R5, D160)
│   │   │   └── state/            # Typed records for the JSON in encounter rows (R5, D168)
│   │   ├── resourcepool/         # Resource pools on characters and participants (M24.5)
│   │   ├── rules/                # Plain-Java rules: dice, expressions, proficiency, spell slots (R3),
│   │   │                         # and the combat rules in rules/combat (R5)
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
│   │   ├── queries/              # TanStack Query hooks and keys (D171)
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
- **`rules`** holds the static calculators `SpellSlotCalculator`, `SpellSlotTables`, `ProficiencyBonus`, `DiceRoller` and `ExpressionEvaluator`, moved with their tests from `character`, `encounter` and `resourcepool`. R5 added `Ability`, `Dice` and `Rng`, and the pure combat rules in `rules.combat` (see [Combat Layout](#combat-layout-r5-d160)).
- **`encounter`** keeps its controllers, entities, DTOs, `EncounterService`, `EncounterLifecycle`, `EncounterCommands` and `EncounterBroadcaster` at the top level. R5 moved the combat services into `encounter.internal`, which only the module's own `CombatController` and `EncounterService` use, and the typed JSON records into `encounter.state`.
- **`shared`** holds the domain exceptions any module may throw: `NotFoundException`, `ForbiddenException`, `ConflictException` and `ValidationException` (D155).
- **`campaign`** holds `AccessPolicy`, which takes IDs rather than entities. `campaign` therefore uses only identity's public API and `shared`, and nothing from `character` or `encounter`, which call the policy (D155).
- **WebSocket interceptors are registered by the module that owns them:** identity's `WebSocketAuthConfig`, at highest precedence, checks CONNECT before encounter's `EncounterSubscriptionConfig` checks SUBSCRIBE and SEND frames. `EncounterWebSocketIT` asserts the order.
- The other packages (`config`, `monster`, `reference`, `resourcepool`, `maintenance`, `seeder`) count as modules for the test but haven't been reorganised yet, and D151's `content` module doesn't exist yet.

`ModuleBoundaryTest` (ArchUnit, `backend/src/test/java/com/tabletophelper/architecture/`, run by `./gradlew test`) checks the production classes, not the tests, against two rules:

1. No class outside a module may use that module's `internal` package.
2. Classes in `rules` may depend only on `java..`, `rules..` and `shared..`: no Spring, no JPA and no other module.

Both rules were checked against deliberate violations. Spring Modulith's `verify()` was not used because today's `character` ↔ `encounter` dependency cycle would fail it.

## Combat Layout (R5, D160)

Every combat command takes one path: check access, lock the encounter's row, resolve, apply, commit, then broadcast. R5 replaced `CombatService`, `SpellResolverEngine`, `MonsterActionResolverEngine` and `MonsterSpellCastService`, which each kept their own copy of the apply logic, with the pieces below.

- **`rules.combat`** is the pure part: no Spring or JPA, and nothing in it changes a participant. `DamageRules`, `AttackRules`, `SaveRules` and `TurnOrder` are functions of the 2014 rules (D161, D163). An `Effect` says what a weapon attack, spell or monster action does: how it reaches its targets (an attack roll, a saving throw, automatically, or on its user only), its damage parts with their types, any healing, and the conditions it gives. `Resolver` rolls an `Effect` against its targets and returns each target's outcome, with dice from an injected `Rng` that tests can script.
- **`encounter.internal`** holds the services that take a command: `TurnService`, `AttackService`, `SpellcastingService`, `MonsterActionService`, `LegendaryResistanceService`, `ConditionService` and `HitPointService`. `SpellEffects` and `MonsterEffects` read the stored spell and monster action templates into `Effect`s. `SpellSources`, `CasterSpells`, `MonsterSpellbook` and `MonsterPools` decide what pays for a spell (D165), and `ManualModifiers` gates numbers entered by hand (D164).
- **`EffectApplier` is the only class that changes a participant's hit points, life state, conditions and concentration.** Weapon attacks, spells, monster actions and the DM's manual changes all end there, and it writes the combat log entries that describe the change.
- **`TurnProcessor` runs every turn boundary:** it ends and starts turns, ends conditions by their end rules, rolls Repeat Saves, gives actions and per-turn pools back, and marks a death save due (D162, D163). `EncounterTurns` computes the turn order and where the encounter stands in it. The encounter stores whose turn it is by participant, not by place in the order.
- **`CombatAccess` starts every combat command**, in this order: access by campaign, then the encounter's row lock, then who the user may act for (D028), and last that the encounter is Active (409 otherwise). The first two steps belong to `EncounterCommands`, which every command that changes an encounter begins with, the DM's lifecycle and initiative commands included. The lock is a `PESSIMISTIC_WRITE` with a five-second wait, and a longer wait is a 409. Commands on one encounter queue behind it, so each starts from the last one's committed state. `EncounterLifecycle` allows only start, pause, resume and complete, and Completed is final (D166).
- **`encounter.state`** holds the records for the JSON inside encounter rows: `ConditionEntry`, `EndRule`, `RepeatSave`, `ConcentrationInstance`, `SpellSlot`, `KnownSpell` and `SpellSource` (D168).
- **The broadcast follows the commit.** A command publishes `EncounterChanged` inside its transaction, and `EncounterBroadcaster` handles it after the commit: it reloads the committed state in a read-only transaction of its own and publishes both views and both logs. A command that rolls back publishes nothing. See [[websocket-protocol#State Broadcast Strategy]].
