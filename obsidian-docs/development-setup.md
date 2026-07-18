# Development Setup

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| Java | 21 (JDK) | e.g. Eclipse Temurin via SDKMAN |
| Node.js | 20 LTS | |
| Docker | Latest | Docker Desktop on macOS |
| Git | Latest | |

## First-Time Setup

```bash
# Clone the repository
git clone <repo-url> questkeeper && cd questkeeper

# Start PostgreSQL
docker compose up -d db

# Verify database is running
docker exec questkeeper-db psql -U questkeeper -d questkeeper -c "SELECT 1"

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
| PostgreSQL | localhost:5432 (user: questkeeper, pass: questkeeper, db: questkeeper) |

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
docker exec -it questkeeper-db psql -U questkeeper -d questkeeper

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
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/questkeeper` | Yes | PostgreSQL JDBC URL |
| `DATABASE_USERNAME` | `questkeeper` | Yes | |
| `DATABASE_PASSWORD` | `questkeeper` | Yes | |

## Troubleshooting

See [[troubleshooting]] for common issues and solutions.
