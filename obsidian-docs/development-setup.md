# Development Setup

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| Java | 21 (JDK) | e.g. Eclipse Temurin via SDKMAN |
| Node.js | 24 LTS | Pinned in `frontend/.nvmrc`; Docker and CI use the same version |
| Docker | Latest | Docker Desktop on macOS |
| Git | Latest | |

## First-Time Setup

```bash
# Clone the repository
git clone <repo-url> tabletophelper && cd tabletophelper

# Start PostgreSQL
docker compose up -d db

# Verify database is running
docker exec tabletophelper-db psql -U tabletophelper -d tabletophelper -c "SELECT 1"

# Start the backend
cd backend
./gradlew bootRun --args='--spring.profiles.active=dev'

# In a separate terminal, start the frontend
cd frontend
npm install
npm run dev
```

## Access Points

| Service | URL |
|---------|-----|
| Frontend | http://localhost:5173 |
| Backend API | http://localhost:8080/api |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| PostgreSQL | localhost:5432 (user: tabletophelper, pass: tabletophelper, db: tabletophelper) |

## Daily Workflow

```bash
# Start database (if not already running)
docker compose up -d db

# Start backend (terminal 1)
cd backend && ./gradlew bootRun --args='--spring.profiles.active=dev'

# Start frontend (terminal 2)
cd frontend && npm run dev
```

The frontend Vite dev server proxies `/api` and `/ws` requests to the backend on port 8080 (configured in `vite.config.ts`). You don't need to worry about CORS during development: since R3 `/ws` checks the page's origin, and the default `CORS_ALLOWED_ORIGINS` (`http://localhost:5173`) already allows the Vite dev server (D157).

## Database Management

```bash
# Connect to psql
docker exec -it tabletophelper-db psql -U tabletophelper -d tabletophelper

# List tables
\dt

# View all users
SELECT * FROM users;

# Stop PostgreSQL (data persists in Docker volume)
docker compose stop db

# Destroy database and start fresh (DELETES ALL DATA)
docker compose down -v
docker compose up -d db
```

Hibernate runs with `ddl-auto: validate` in every profile, dev included, and Flyway applies the migrations in `backend/src/main/resources/db/migration` at startup (D097). Every schema change needs a new migration; see [[database-schema#Flyway Migration History]]. (An earlier version of this page said dev used `ddl-auto: update` and needed no migrations; that stopped being true with D097.)

## Environment Variables

| Variable | Default (Dev) | Required (Prod) | Description |
|----------|--------------|-----------------|-------------|
| `JWT_SECRET` | Not read: dev uses its built-in secret from `application-dev.yml` | Yes | At least 32 bytes (64 or more gives HS512). Outside dev there is no fallback, and startup fails when it is missing or shorter (D156). |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | Yes, once HTTPS is on | Origins allowed for cross-origin requests and for `/ws` (D157). Leave it unset locally; see [[deployment-guide]] for production. |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/tabletophelper` | Yes | PostgreSQL JDBC URL |
| `DATABASE_USERNAME` | `tabletophelper` | Yes | |
| `DATABASE_PASSWORD` | `tabletophelper` | Yes | |

## Signing In and Errors in Development (R3)

- **Everyone has to sign in once after R3.** Tokens issued before it have no `type` claim, so the backend refuses them, and no browser holds the refresh cookie yet. On first load the app also deletes the old `accessToken`, `refreshToken` and `user` keys from localStorage.
- **Dev uses its built-in JWT secret and a non-Secure cookie.** `application-dev.yml` sets the secret and `app.auth.cookie-secure: false`, because localhost runs on plain HTTP and browsers only store a Secure cookie over HTTPS. Every other profile sends the cookie with Secure and needs a `JWT_SECRET` of at least 32 bytes, or startup fails with `JWT_SECRET must be set to at least 32 bytes, but app.jwt.secret has N` (D156). The integration tests' `it` profile (`backend/src/integrationTest/resources/application-it.yml`) sets a secret of its own.
- **Where the session lives.** The access token is only in memory, so DevTools shows nothing in localStorage. The `refresh_token` cookie (HttpOnly, SameSite=Strict, Path `/api/auth`) appears under the cookies for `localhost:5173`. Reloading a page shows "Loading..." briefly while the app trades the cookie for an access token. See [[auth-flow]].
- **Error messages.** Only the dev profile shows the real message of an unexpected error (`server.error.include-message: always`); other profiles return "Something went wrong. Please try again." Status codes follow D155: 401 for a missing or expired token, 403 for "not allowed", 404 for a missing resource named in the URL, 409 for the wrong state. See [[api-reference#Error Response Format]].

## Generated API types

The frontend's API types come from the backend's OpenAPI spec (D171). After changing a controller or DTO, start the dev backend, run `cd frontend && npm run api:spec`, and commit `src/api/generated/`. `api:spec` saves the spec from `BACKEND_URL` (default `http://localhost:8080`) and regenerates the types; `npm run api:types` regenerates the types from the committed spec alone. CI fails when the committed types don't match the committed spec, and `OpenApiSpecIT` fails when the committed spec doesn't match the backend. A property is nullable unless its DTO marks it `@Schema(requiredMode = REQUIRED)`.

## Continuous Integration

GitHub Actions (`.github/workflows/ci.yml`) runs on every push to `main` and every pull request:

- **Backend tests:** `./gradlew test` on Java 21. These are unit tests and don't need Docker. They include `ModuleBoundaryTest` (ArchUnit, D151), which fails when a class uses another module's `internal` package or when `rules` depends on anything but the JDK and `shared`; see [[architecture#Module Boundaries (D151)]]. Since R6 workstream D it also fails when a class in `character` depends on anything in `encounter`.
- **Backend integration tests:** `./gradlew integrationTest` runs the tests in `backend/src/integrationTest` against a real PostgreSQL 16 started by Testcontainers. They cover Flyway from an empty database, the custom queries, HTTP security status codes, the refresh cookie, the Player View and the encounter WebSocket's subscription checks (D172). Since R5 they also cover the combat engine and its data end to end: the encounter lifecycle (`EncounterLifecycleIT`), commands queueing behind the encounter's row lock (`ConcurrentCombatIT`), the typed JSON columns (`EncounterJsonMappingIT`), the `preview-migration` job (`PreviewMigrationJobIT`), Legendary Resistance (`LegendaryResistanceIT`), turn order and turn boundaries (`TurnOrderIT`, `TurnBoundaryIT`), spellcasting (`SpellcastingIT`), weapon attacks (`WeaponAttackIT`), Multiattack (`MultiattackIT`) and the saving throws a monster's hit calls for against its conditions (`HitSaveIT`). Since R6 they also cover the typed character columns and the character API's structured JSON (`CharacterJsonMappingIT`). Since R6 workstream C they also cover levelling up and down end to end, with every Derived Stat checked, and the `recompute-characters` job (`ProgressionIT`), and `PreviewMigrationJobIT` also applies V20. Since R6 workstream D they also cover the character commands (`CharacterCommandsIT`): each command over HTTP, the owner-only checks, the rests' entered and scripted dice, the refusals during an Active or Paused encounter, commands queueing behind the character's row lock, the 409 after a five-second wait for it, and the version catching a stale write. `PreviewMigrationJobIT` applies V21; its previews of V19 and V20 gave way to V21's, because a preview loads characters with today's mappings, which need `version`. Since R6 workstream E they also cover DM Adjustments (`DmAdjustmentIT`): only the DM of the character's campaign adds or removes one, STAT adjustments reaching the Derived Stats, a DEFENCE reaching a participant in a Live Encounter at once and counting once, FEAT grants and their exact reversal, a repeatable feat granted twice, and the 400s for a request its kind doesn't fit. `CharacterJsonMappingIT` round-trips an adjustment of each kind. `PreviewMigrationJobIT` applies V22, and V21's preview gave way to V22's, because the mappings now need `dm_adjustments` too; V21's apply test stays. Since R6 workstream F they also cover Pending Choices (`PendingChoicesIT`): creation owing the choices of levels 1 to N and level-up adding the new level's, a known caster created at 5th level learning its later levels' spells with a swap, a subclass recorded on its level with its features and the choices it brings, a 409 for a choice the character doesn't have and a 400 for an answer that doesn't fit, each changing nothing, choices left pending through an encounter and refused while it is under way, levelling down removing and reversing them, a Variant Human's feat with its pool and initiative, and the DM seeing the choices but getting 403 for making one. `CharacterJsonMappingIT` round-trips the Pending Choices and a level's new choice records. `PreviewMigrationJobIT` previews and applies V23, and V22's preview gave way to V23's, because the mappings now need `pending_choices`; V22's apply test stays. `ScriptedDiceConfiguration` lets a test script the next dice rolls with `dice.script(...)`; rolls it doesn't script are random.
- **Frontend:** `npm run lint`, `npm run build` (type check plus bundle) and `npm test`.
- **Docker smoke test:** builds and starts the production stack, then checks that the frontend responds, the backend health endpoint reports UP, and both containers run as non-root users.

To run the same checks locally before pushing:
- `cd backend && ./gradlew test`
- `cd backend && ./gradlew integrationTest` — needs Docker Desktop running. Without it the task stops at once with "Docker isn't running: start Docker Desktop and rerun." It isn't part of `./gradlew build`.
- `cd frontend && npm run lint && npm run build && npm test`

Frontend tests run with MSW in strict mode: a test fails if it makes a request no handler covers (`frontend/vitest.setup.ts`). Add a handler to `src/test/mocks/handlers.ts`, or use `server.use(...)` in the test. STOMP and SockJS are mocked globally; tests drive the encounter socket with `MockStompClient` from `src/test/mocks/stomp.ts`.

## Troubleshooting

See [[troubleshooting]] for common issues and solutions.
