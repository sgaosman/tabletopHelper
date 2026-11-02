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

The frontend Vite dev server proxies `/api` and `/ws` requests to the backend on port 8080 (configured in `vite.config.ts`). You don't need to worry about CORS during development.

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

Hibernate `ddl-auto: update` creates and alters tables automatically when entities change. You never need to write SQL migrations in development. For production, `ddl-auto: validate` is used — schema changes must be managed explicitly.

## Environment Variables

| Variable | Default (Dev) | Required (Prod) | Description |
|----------|--------------|-----------------|-------------|
| `JWT_SECRET` | Hardcoded dev fallback | Yes | 64+ byte secret for HS512 JWT signing |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/tabletophelper` | Yes | PostgreSQL JDBC URL |
| `DATABASE_USERNAME` | `tabletophelper` | Yes | |
| `DATABASE_PASSWORD` | `tabletophelper` | Yes | |

## Continuous Integration

GitHub Actions (`.github/workflows/ci.yml`) runs on every push to `main` and every pull request:

- **Backend tests:** `./gradlew test` on Java 21. These are unit tests and don't need Docker.
- **Backend integration tests:** `./gradlew integrationTest` runs the tests in `backend/src/integrationTest` against a real PostgreSQL 16 started by Testcontainers. They cover Flyway from an empty database, the custom queries, HTTP security status codes and the encounter WebSocket (D172).
- **Frontend:** `npm run lint`, `npm run build` (type check plus bundle) and `npm test`.
- **Docker smoke test:** builds and starts the production stack, then checks that the frontend responds, the backend health endpoint reports UP, and both containers run as non-root users.

To run the same checks locally before pushing:
- `cd backend && ./gradlew test`
- `cd backend && ./gradlew integrationTest` — needs Docker Desktop running. Without it the task stops at once with "Docker isn't running: start Docker Desktop and rerun." It isn't part of `./gradlew build`.
- `cd frontend && npm run lint && npm run build && npm test`

Frontend tests run with MSW in strict mode: a test fails if it makes a request no handler covers (`frontend/vitest.setup.ts`). Add a handler to `src/test/mocks/handlers.ts`, or use `server.use(...)` in the test. STOMP and SockJS are mocked globally; tests drive the encounter socket with `MockStompClient` from `src/test/mocks/stomp.ts`.

## Troubleshooting

See [[troubleshooting]] for common issues and solutions.
