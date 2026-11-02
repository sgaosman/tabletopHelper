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

## Continuous Integration

GitHub Actions (`.github/workflows/ci.yml`) runs on every push to `main` and every pull request:

- **Backend tests:** `./gradlew test` on Java 21. These are unit tests and don't need Docker. They include `ModuleBoundaryTest` (ArchUnit, D151), which fails when a class uses another module's `internal` package or when `rules` depends on anything but the JDK and `shared`; see [[architecture#Module Boundaries (D151)]].
- **Backend integration tests:** `./gradlew integrationTest` runs the tests in `backend/src/integrationTest` against a real PostgreSQL 16 started by Testcontainers. They cover Flyway from an empty database, the custom queries, HTTP security status codes, the refresh cookie, the Player View and the encounter WebSocket's subscription checks (D172).
- **Frontend:** `npm run lint`, `npm run build` (type check plus bundle) and `npm test`.
- **Docker smoke test:** builds and starts the production stack, then checks that the frontend responds, the backend health endpoint reports UP, and both containers run as non-root users.

To run the same checks locally before pushing:
- `cd backend && ./gradlew test`
- `cd backend && ./gradlew integrationTest` — needs Docker Desktop running. Without it the task stops at once with "Docker isn't running: start Docker Desktop and rerun." It isn't part of `./gradlew build`.
- `cd frontend && npm run lint && npm run build && npm test`

Frontend tests run with MSW in strict mode: a test fails if it makes a request no handler covers (`frontend/vitest.setup.ts`). Add a handler to `src/test/mocks/handlers.ts`, or use `server.use(...)` in the test. STOMP and SockJS are mocked globally; tests drive the encounter socket with `MockStompClient` from `src/test/mocks/stomp.ts`.

## Troubleshooting

See [[troubleshooting]] for common issues and solutions.
