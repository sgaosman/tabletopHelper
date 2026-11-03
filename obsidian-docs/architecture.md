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
         │  │  CharacterCommands (R6)     │  │
         │  │  CharacterMapper            │  │
         │  │  CharacterSheetService (R6) │  │
         │  │  CharacterSpellService (R6) │  │
         │  │  CharacterStats (R6)        │  │
         │  │  CharacterUpdates           │  │
         │  │  Combat services (R5)       │  │
         │  │  DmAdjustmentService (R6)   │  │
         │  │  DmAdjustments (R6)         │  │
         │  │  EncounterService (M4)      │  │
         │  │  FeatEffectResolver         │  │
         │  │  PendingChoiceService (R6)  │  │
         │  │  PendingChoices (R6)        │  │
         │  │  RestService (R6)           │  │
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
│   │   │   ├── internal/         # CharacterStats, which works out the Derived Stats (R6, D167)
│   │   │   └── state/            # Typed records for the JSON in character rows (R6, D168)
│   │   ├── monster/              # (Milestone 3)
│   │   ├── reference/            # Spells, conditions, items (Milestone 3)
│   │   ├── encounter/            # Encounters and combat (there is no top-level combat/ package),
│   │   │   │                     # broadcaster, DM/Player Views, subscription guard
│   │   │   ├── internal/         # The combat services, EffectApplier, TurnProcessor (R5, D160)
│   │   │   └── state/            # Typed records for the JSON in encounter rows (R5, D168)
│   │   ├── resourcepool/         # Resource pools on characters and participants (M24.5)
│   │   ├── rules/                # Plain-Java rules: dice, expressions, proficiency, spell slots (R3),
│   │   │                         # the combat rules in rules/combat (R5) and the character rules in rules/character (R6)
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
- **`rules`** holds the static calculators `SpellSlotCalculator`, `SpellSlotTables`, `ProficiencyBonus`, `DiceRoller` and `ExpressionEvaluator`, moved with their tests from `character`, `encounter` and `resourcepool`. R5 added `Ability`, `Dice` and `Rng`, and the pure combat rules in `rules.combat` (see [Combat Layout](#combat-layout-r5-d160)). R6 workstream A added `Ability.modifier(score)`, the one ability modifier, rounded down as the PHB requires (p.13), which replaced seven copies, and `rules.character` (D167): the character rules as pure functions, with golden tests typed from the PHB. `ClassProgression` holds what a class gains at each level (Ability Score Improvement, expertise and fighting style levels, cantrips and spells known, prepared-spell limits and the highest spell level), `HitPoints` the hit point maximum, `HitDice` what a long rest restores of the Hit Dice, `Rests` the healing from a Hit Die spent on a short rest, `AbilityScores` the maximum a score can be raised to and `Multiclassing` every class's prerequisites. Since workstream C, `character.internal.CharacterStats` calls them. Workstream D adds `CurrentHitPoints`, the sheet's hit points between encounters: damage comes off temporary hit points first and stops at 0, healing stops at the maximum, and temporary hit points keep the larger amount (D158). `RestService` spends and recovers Hit Dice with `Rests` and `HitDice`. Workstream F adds to `ClassProgression` the Fighting Styles each class chooses from (`fightingStyles`, the PHB's and then Tasha's) and the spells a Wizard copies into its spellbook for free at each level (`spellbookSpells`), which `character`'s `PendingChoices` reads (D159).
- **`encounter`** keeps its controllers, entities, DTOs, `EncounterService`, `EncounterLifecycle`, `EncounterCommands` and `EncounterBroadcaster` at the top level. R5 moved the combat services into `encounter.internal`, which only the module's own `CombatController` and `EncounterService` use, and the typed JSON records into `encounter.state`. R6 moved `SpellSlot` and `KnownSpell` on to `character.state`.
- **`character`** keeps its controller, entity, DTOs, services and `CharacterUpdates` at the top level. R6 workstream B added `character.state`, which both `character` and `encounter` use: the typed records for the JSON in `player_characters` rows (D168), namely `Feature`, `InventoryItem`, `Currency`, `ClassEntry`, `AbilityBonus`, `HitDicePool`, `LevelHistoryEntry` (with `LevelChoices`, `AbilityScoreImprovement` and `AppliedFeatEffects`), `FeatResourceEntry`, `ResourcePoolEntry`, `SpellSlot` and `KnownSpell`. `SpellSlot` and `KnownSpell` came from `encounter.state`, and `LevelHistoryEntry`, `FeatResourceEntry` and `ResourcePoolEntry` from `character.dto`. The character API returns these records as structured JSON rather than JSON text. `CharacterUpdates` replaced `CharacterJsonHelper`: the records are immutable, so it makes the changes that levelling up and down and applying choices share (features, class entries, ability scores, proficiencies and the level history) by building a new list and setting it. Since workstream C, Hit Dice are worked out with the other Derived Stats. Since workstream D every player command on a character starts and ends in `CharacterCommands`, as an encounter's commands start in `EncounterCommands` (D158). It checks the owner's ID before locking, so another user never takes the lock. It sets a five-second lock wait with `set_config('lock_timeout', '5000ms', true)`, because Hibernate's SQL for the lock, `FOR NO KEY UPDATE`, ignores the lock timeout hint on PostgreSQL. It then locks the character row. It refuses rests and levelling while the character is in a Live Encounter, and at the end derives the stats through `CharacterStats` and saves. `@Version` on `PlayerCharacter` (V21) is the backstop: a stale write answers 409. The commands live in `CharacterSheetService` (details, campaign, inventory, HP, slots and pools), `RestService` (short and long rests) and `CharacterSpellService` (prepared spells, the Wizard's spellbook and, since workstream F, the cantrips and spells a level's Pending Choices learn), and `CharacterService` keeps creation, levelling, deletion and reading. `InventoryChecks` checks an inventory at creation and on every change. Since workstream D `character` also uses nothing from `encounter`: it asks whether a character is in a Live Encounter through its own `LiveEncounters` interface, which `encounter`'s `ParticipantLiveEncounters` implements, so the dependency runs from encounter to character, and `ModuleBoundaryTest` forbids any dependency the other way (D151). `CharacterMapper` puts the answer in every response as `inLiveEncounter`. Since workstream E the DM of a character's campaign changes it only through DM Adjustments, in `DmAdjustmentService` (D158). Its commands start with `CharacterCommands.beginAsDm`, which reads only the character's and campaign's IDs and checks them with `AccessPolicy.requireCharacterDm`, so a caller who may not adjust the character never locks its row. It then takes the same lock wait and row lock as a player command, checks the campaign again under the lock, and ends with the same derivation and save. It isn't refused during a Live Encounter. The adjustments are `character.state.DmAdjustment` entries in `dm_adjustments` (V22). `character`'s public `DmAdjustments` works only from the sheet: the ability scores, AC and damage defences with the active adjustments on top, and every feat taken, from the level history and the active FEAT grants. The stored scores and AC stay without adjustments, so whatever reads a score, the AC or a defence for a rule goes through it: `CharacterStats`, `CharacterMapper`, `MulticlassValidator` and `RestService` in `character`, `ResourcePoolService` in `resourcepool`, and `CreatureStats`, `CharacterWeapons` and `EncounterService` in `encounter` (see [Combat Layout](#combat-layout-r5-d160)). On the frontend the DM opens `DmAdjustmentsModal` from each character card on the campaign page. Since workstream F the choices a character's levels owe are Pending Choices, kept on the sheet as `character.state.PendingChoice` entries in `pending_choices` (V23, D159). The package-private `PendingChoices` works out what a class level owes from `rules.character.ClassProgression`, as pure code: a subclass, a Fighting Style, an ASI, Expertise, cantrips and spells. `PendingChoiceService` adds a level's choices when `CharacterService` creates or levels a character, and undoes the last level's when it levels down. It also makes one through `apply-choices`, which starts with `CharacterCommands.beginOutsideEncounters` and ends with `finish`, and records the answer on the level that owes it through `CharacterUpdates`. Its cantrips and spells go through `CharacterSpellService`'s `learnCantrips`, `learnSpells` and `forget`, and its feats through `FeatEffectResolver`. `LevelChoices` gains the new records `SubclassChosen`, `SpellsLearned` and `FeatTaken`. `DmAdjustments.featsTaken` counts a feat taken at creation, and `DmAdjustments.hasFeat`, moved there from `DmAdjustmentService`, answers whether a character has a feat, for apply-choices and DM Adjustments alike. `maintenance`'s `backfill-pending-choices` job asks `PendingChoiceService.missedChoices` what each character's levels missed and adds it through `addPending`. On the frontend the sheet lists the choices on `PendingChoicesBanner` and opens each in `ChoiceDialog`.
- **`character.internal`** (R6 workstream C, D167) holds `CharacterStats`, the single derivation of the Derived Stats from the sheet, the level history and `rules.character`: the proficiency bonus, HP maximum, initiative, speed, ability scores and their maximums, slot and Hit Dice maximums, each class's spell numbers, the main spell numbers, and the resource pools through `ResourcePoolService.reconciledPools`. Creation, levelling, applied choices and, since workstream D, every other character command end with its `apply`, which writes the stored copies, and `CharacterMapper` adds each class's spell numbers and the ability score maximums to the response when it is read. Since workstream D the pools include a `feat:<feat>` pool for each feat with limited uses that the level history records (D158). Other modules use it through `CharacterService`: its result, `DerivedStats`, is public in `character`, `maintenance`'s `recompute-characters` job calls `derivedStats` and `recalculate`, and `encounter`'s `SpellcastingService` calls `classSpellcasting`. Workstream C also adds `ProficienciesGained` to `character.state`, and the response records `ClassSpellcasting` and `AbilityScoreMaximums` to `character.dto`. Since workstream E the derivation includes the DM Adjustments: the ability adjustments first, so everything worked out from the scores uses the adjusted ones, then the HP maximum, initiative, speed and spell adjustments on top. A feat the DM granted counts as one the level history records (D158). Since workstream F so does a feat a race or background gave at creation, which the 1st level records, so its speed, initiative, HP per level and pool count (D159).
- **`shared`** holds the domain exceptions any module may throw: `NotFoundException`, `ForbiddenException`, `ConflictException` and `ValidationException` (D155).
- **`campaign`** holds `AccessPolicy`, which takes IDs rather than entities. `campaign` therefore uses only identity's public API and `shared`, and nothing from `character` or `encounter`, which call the policy (D155). Since R6 workstream E it has `requireCharacterDm`, the check for DM Adjustments: only the DM of the character's campaign passes, and nobody for a character in no campaign (D158).
- **WebSocket interceptors are registered by the module that owns them:** identity's `WebSocketAuthConfig`, at highest precedence, checks CONNECT before encounter's `EncounterSubscriptionConfig` checks SUBSCRIBE and SEND frames. `EncounterWebSocketIT` asserts the order.
- The other packages (`config`, `monster`, `reference`, `resourcepool`, `maintenance`, `seeder`) count as modules for the test but haven't been reorganised yet, and D151's `content` module doesn't exist yet.

`ModuleBoundaryTest` (ArchUnit, `backend/src/test/java/com/tabletophelper/architecture/`, run by `./gradlew test`) checks the production classes, not the tests, against three rules:

1. No class outside a module may use that module's `internal` package.
2. Classes in `rules` may depend only on `java..`, `rules..` and `shared..`: no Spring, no JPA and no other module.
3. Classes in `character` may depend on nothing in `encounter` (R6 workstream D). `character` asks what it needs of encounters through interfaces of its own, such as `LiveEncounters`, which `encounter` implements.

The first two rules were checked against deliberate violations. Spring Modulith's `verify()` was not used because the dependency cycles between modules would fail it: since R6 workstream D `character` no longer depends on `encounter`, but the two still meet in a cycle through `resourcepool`, which uses both and which both use.

## Combat Layout (R5, D160)

Every combat command takes one path: check access, lock the encounter's row, resolve, apply, commit, then broadcast. R5 replaced `CombatService`, `SpellResolverEngine`, `MonsterActionResolverEngine` and `MonsterSpellCastService`, which each kept their own copy of the apply logic, with the pieces below.

- **`rules.combat`** is the pure part: no Spring or JPA, and nothing in it changes a participant. `DamageRules`, `AttackRules`, `SaveRules` and `TurnOrder` are functions of the 2014 rules (D161, D163). An `Effect` says what a weapon attack, spell or monster action does: how it reaches its targets (an attack roll, a saving throw, automatically, or on its user only), its damage parts with their types, any healing, and the conditions it gives. An attack roll can carry a `HitSave`, the saving throw a target it hits makes against some of those conditions, which the target gets only on a failure (R5 follow-ups, D165). `Resolver` rolls an `Effect` against its targets and returns each target's outcome, with dice from an injected `Rng` that tests can script.
- **`encounter.internal`** holds the services that take a command: `TurnService`, `AttackService`, `SpellcastingService`, `MonsterActionService`, `LegendaryResistanceService`, `ConditionService` and `HitPointService`. `SpellEffects` and `MonsterEffects` read the stored spell and monster action templates into `Effect`s. `SpellSources`, `CasterSpells`, `MonsterSpellbook` and `MonsterPools` decide what pays for a spell (D165), and `ManualModifiers` gates numbers entered by hand (D164). Since R6 workstream C, `SpellcastingService` casts a player character's class spell with that class's save DC and attack bonus, from `CharacterService.classSpellcasting`, and a race or feat spell with the main numbers (D164). Since R6 workstream E, `CreatureStats` reads a player character's ability scores and damage defences live through `character/DmAdjustments`, with the DM Adjustments, and `CharacterWeapons` its STR and DEX, so saves, weapon attacks and damage use them at once, during a Live Encounter too. `EncounterService` copies a joining character's AC with its AC adjustments, beside the stored HP maximum, initiative and main spell numbers, which already have theirs (D158, D161).
- **`EffectApplier` is the only class that changes a participant's hit points, life state, conditions and concentration.** Weapon attacks, spells, monster actions and the DM's manual changes all end there, and it writes the combat log entries that describe the change.
- **`TurnProcessor` runs every turn boundary:** it ends and starts turns, ends conditions by their end rules, rolls Repeat Saves, gives actions and per-turn pools back, and marks a death save due (D162, D163). `EncounterTurns` computes the turn order and where the encounter stands in it. The encounter stores whose turn it is by participant, not by place in the order.
- **`CombatAccess` starts every combat command**, in this order: access by campaign, then the encounter's row lock, then who the user may act for (D028), and last that the encounter is Active (409 otherwise). The first two steps belong to `EncounterCommands`, which every command that changes an encounter begins with, the DM's lifecycle and initiative commands included. The lock is a `PESSIMISTIC_WRITE` with a five-second wait, and a longer wait is a 409. Commands on one encounter queue behind it, so each starts from the last one's committed state. `EncounterLifecycle` allows only start, pause, resume and complete, and Completed is final (D166).
- **`encounter.state`** holds the records for the JSON inside encounter rows: `ConditionEntry`, `EndRule`, `RepeatSave`, `ConcentrationInstance` and `SpellSource` (D168). A participant's spell slots and known spells are `character.state`'s `SpellSlot` and `KnownSpell` (R6), the records the character sheet uses.
- **The broadcast follows the commit.** A command publishes `EncounterChanged` inside its transaction, and `EncounterBroadcaster` handles it after the commit: it reloads the committed state in a read-only transaction of its own and publishes both views and both logs. A command that rolls back publishes nothing. See [[websocket-protocol#State Broadcast Strategy]].
