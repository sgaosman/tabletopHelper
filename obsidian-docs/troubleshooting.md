# Troubleshooting

## Backend Won't Start

### Port 8080 already in use

```
Web server failed to start. Port 8080 was already in use.
```

**Cause:** A previous backend instance is still running.

**Fix:**
```bash
lsof -ti:8080 | xargs kill -9
```

### Database connection refused

```
Connection to localhost:5432 refused
```

**Cause:** PostgreSQL container isn't running.

**Fix:**
```bash
docker compose up -d db
# Wait a few seconds for PostgreSQL to initialise
docker exec questkeeper-db pg_isready
```

### Hibernate schema errors after entity changes

**Cause:** `ddl-auto: update` can't always alter existing columns (e.g. changing a column type or adding NOT NULL to a populated column).

**Fix:** Reset the database and let Hibernate recreate the schema:
```bash
docker compose down -v     # Deletes the volume (ALL DATA LOST)
docker compose up -d db    # Fresh database
```

## Frontend Issues

### Blank page (no errors visible)

**Cause:** Usually a JavaScript runtime error that prevents React from rendering. Most common cause: importing TypeScript interfaces with `import { ... }` instead of `import type { ... }`. Vite 8's Rolldown bundler strips type-only exports.

**Fix:** Check the browser console (F12 → Console). If you see `MISSING_EXPORT` errors, change the imports:
```typescript
// Bad — fails with Vite 8 / Rolldown
import { MyInterface } from '../types/foo';

// Good
import type { MyInterface } from '../types/foo';
```

### API calls return 403

**Cause:** JWT token is missing, expired, or invalid.

**Checks:**
1. Is the user logged in? Check `localStorage.getItem('accessToken')` in browser console.
2. Is the token expired? Decode it at jwt.io and check the `exp` field.
3. Is the backend running? Try `curl http://localhost:8080/api/auth/login -X POST -H 'Content-Type: application/json' -d '{"username":"test","password":"test"}'`

### Frontend can't reach backend API

**Cause:** Vite proxy not configured or backend not running.

**Check:**
```bash
# Test direct backend access
curl http://localhost:8080/api/auth/login -X POST -H 'Content-Type: application/json' -d '{}'

# Test through Vite proxy
curl http://localhost:5173/api/auth/login -X POST -H 'Content-Type: application/json' -d '{}'
```

If direct works but proxy doesn't, check `vite.config.ts` proxy configuration.

### macOS firewall popup for Node.js

**Symptom:** macOS asks "Do you want the application Node.js to accept incoming network connections?"

**Impact:** None for local development. This prompt is about connections from other devices on the network. `localhost` connections work regardless. Click "Deny" if your machine is locked down — the app still works locally.

## Database

### Checking what's in the database

```bash
# List all tables
docker exec questkeeper-db psql -U questkeeper -d questkeeper -c "\dt"

# Count rows in each table
docker exec questkeeper-db psql -U questkeeper -d questkeeper -c "
  SELECT 'users' AS tbl, COUNT(*) FROM users
  UNION ALL SELECT 'campaigns', COUNT(*) FROM campaigns
  UNION ALL SELECT 'campaign_members', COUNT(*) FROM campaign_members
  UNION ALL SELECT 'player_characters', COUNT(*) FROM player_characters;
"

# View a specific user
docker exec questkeeper-db psql -U questkeeper -d questkeeper -c "SELECT id, username, email, display_name FROM users;"
```

### Data survives container restarts

PostgreSQL data is stored in a Docker named volume (`questkeeper-data`), not in the container filesystem. Stopping, restarting, or even removing the container preserves data. Only `docker compose down -v` (the `-v` flag) deletes volumes.

```bash
# Check the volume exists
docker volume ls | grep questkeeper

# Inspect it
docker volume inspect dmscreen_questkeeper-data
```

### Reset everything

```bash
docker compose down -v     # Remove containers AND volumes
docker compose up -d db    # Fresh database, empty tables
```

### Raw 5e.tools markup in monster stat blocks (e.g. `{@h}`, `{@atk mw}`)

**Cause:** Monster traits/actions/reactions are stored as raw JSONB via `@JsonRawValue`, bypassing server-side markup parsing. The frontend must parse 5e.tools tags itself.

**Fix:** The shared `parseMarkup` utility (`frontend/src/utils/parseMarkup.ts`) handles all 5e.tools tags. Components rendering monster descriptions must use `dangerouslySetInnerHTML={{ __html: parseMarkup(description) }}` instead of rendering the string directly. Spell and item descriptions don't need this — they're parsed server-side by `FiveEToolsMarkupParser`.

**Note:** The regex must use an optional content group `(?:\s+([^}]*?))?` to handle content-less tags like `{@h}`.

## Common Errors

### "Invalid UUID string: undefined"

**Cause:** A frontend route or API call is passing the string `"undefined"` where a UUID is expected. Usually caused by a `useParams()` returning `undefined` for a route parameter that doesn't exist.

**Fix:** Check the route definition in `App.tsx`. Make sure the route has the expected path parameter (`:id`, `:characterId`, etc.) and that the component handles the case where the parameter is missing.

### "Username already taken" / "Email already registered"

**Cause:** Registration attempted with a username or email that already exists.

**Fix:** Use a different username/email, or check existing users:
```bash
docker exec questkeeper-db psql -U questkeeper -d questkeeper -c "SELECT username, email FROM users;"
```

### Gradle build fails with "Could not resolve dependencies"

**Cause:** Network issue or Gradle cache corruption.

**Fix:**
```bash
cd backend
./gradlew --refresh-dependencies compileJava
# Or clean build
./gradlew clean build
```
