# Risk Register

Risks are rated by **severity** (impact if realised) and **likelihood** (probability of occurring). Each has documented mitigations.

## Active Risks

### R001: WebSocket Reliability During Combat

**Category:** Networking
**Severity:** High — a dropped connection mid-combat desync the DM or a player
**Likelihood:** Medium — mobile networks and corporate Wi-Fi are unreliable
**Status:** Open (mitigations planned for M4/M6)

**Mitigations:**
- SockJS provides automatic fallback to HTTP long-polling when WebSockets are blocked
- Auto-reconnect on disconnect with full state resync (client re-subscribes and receives current state)
- The server is always authoritative — no client-side state can diverge permanently
- Toast notification on disconnect so users know to wait for reconnection

### R002: Combat Mechanics Complexity

**Category:** Feature
**Severity:** Medium — edge cases in D&D rules could produce incorrect results
**Likelihood:** High — D&D 5e has many interacting mechanics (resistance stacking, multi-attack, legendary actions, area spells)
**Status:** Open (managed by design)

**Mitigations:**
- Implement core mechanics first: single-target attacks, single-target spells, basic conditions, death saves
- Leave complex interactions for the DM to adjudicate manually — the system tracks state and lets the DM override anything
- The DM can always apply arbitrary damage/healing/conditions directly
- Automated conditions limited to mechanically simple ones (unconscious, prone, stunned); contextual conditions rely on DM judgment

### R003: Character Sheet Complexity

**Category:** Feature
**Severity:** Low — incomplete character sheet is annoying but not blocking
**Likelihood:** High — a full 5e character sheet has dozens of fields and many derived values
**Status:** Partially mitigated

**Current state:** Character creation uses free-text inputs for race/class. No derived stat calculation.

**Planned mitigations (post-M3):**
- Constrain race/class/subclass to dropdowns populated from seeded 5e data
- Offer standard ability score methods: point buy, standard array, 4d6 drop lowest
- Auto-calculate derived stats (proficiency bonus from level, ability modifiers, etc.)
- See [[../memory/feedback_character-creation-ux.md]] for full requirements

### R004: Data Seeding Performance

**Category:** Performance
**Severity:** Low — only affects first startup
**Likelihood:** Medium — 2000+ monsters, 500+ spells, 1000+ items is significant
**Status:** Open (M3)

**Mitigations:**
- Seeder is idempotent — checks `count() == 0` before seeding each type
- Uses batch inserts (`saveAll()` with lists of 100)
- Logs progress during seeding
- First-run may take 1-2 minutes, which is acceptable for a one-time operation

### R005: JWT Token Security

**Category:** Security
**Severity:** Medium — a stolen token grants full account access until expiry
**Likelihood:** Low — small trusted user group
**Status:** Accepted

**Mitigations:**
- Access tokens expire after 1 hour (limits window of exposure)
- Refresh tokens expire after 7 days
- JWT secret stored in environment variable, not source code
- CORS restricts origins to the frontend domain only
- HTTPS in production (TLS via Let's Encrypt)

**Not mitigated (accepted):**
- No server-side token revocation/blacklist — complexity not warranted for 9 users
- Tokens stored in localStorage (vulnerable to XSS) — acceptable for personal-use app with no financial data

### R006: Single Point of Failure (VPS)

**Category:** Infrastructure
**Severity:** High — if the VPS goes down during a game session, combat stops
**Likelihood:** Low — Hetzner has 99.9% SLA, but hardware failures happen
**Status:** Open

**Mitigations:**
- PostgreSQL data persists in Docker volume — survives container/app restarts
- Application recovers from crashes automatically (Docker restart policy: `unless-stopped`)
- Full encounter state is in the database — even a complete server restart only causes a brief interruption, not data loss
- Consider automated database backups (pg_dump cron job) for production

### R007: Scope Creep

**Category:** Project
**Severity:** Medium — delays the encounter engine, which is the core product
**Likelihood:** High — the feature list is extensive and it's tempting to polish before the combat engine works
**Status:** Managed

**Mitigations:**
- Milestone order is strict — encounter engine is the product, everything else supports it
- Future features are documented but explicitly out of scope for month one
- Character sheet improvements deferred to post-M3 when reference data is available
- "Good enough" over "perfect" for non-combat features

### R008: Mobile Responsiveness

**Category:** UX
**Severity:** Medium — players primarily use phones at the table
**Likelihood:** Medium — desktop-first development may neglect mobile layouts
**Status:** Open (M6)

**Mitigations:**
- Tailwind CSS utility classes make responsive design incremental
- Mobile layout planned: collapsed initiative list at top, current participant info in middle, action buttons at bottom
- Test with real phones during M6

### R009: 5e.tools Data Format Changes

**Category:** External dependency
**Severity:** Low — only affects data seeding, not runtime
**Likelihood:** Low — we're using the frozen 2014 edition data, which is no longer updated
**Status:** Low priority

**Mitigations:**
- Using the 2014 5e.tools dataset specifically (not the main site which includes 2024 content)
- Data is downloaded once and stored in the repository — no runtime dependency on external services
- Flexible Jackson deserialization handles format variations (e.g. `ac` as int or array, `cr` as string or object)

## Resolved Risks

### R-RESOLVED-001: Vite 8 Build Failures with Type Imports

**Category:** Tooling
**Resolved:** 2026-07-17

**Problem:** Vite 8 uses Rolldown bundler which strips type-only exports at build time. Importing TypeScript interfaces with regular `import { ... }` caused `MISSING_EXPORT` errors in production builds and blank pages in dev.

**Resolution:** Use `import type { ... }` for all type-only imports. Applied to `AuthContext.tsx` and `authApi.ts`.

### R-RESOLVED-002: Character Creation UUID Parse Error

**Category:** Bug
**Resolved:** 2026-07-17

**Problem:** Creating a new character at `/player/characters/new` failed with "Invalid UUID string: undefined". The route `/player/characters/new` didn't have a `:characterId` param, so `useParams()` returned `undefined`. The `isNew` check (`characterId === 'new'`) evaluated to `false`, causing the save handler to call `PUT /api/characters/undefined`.

**Resolution:** Changed `isNew` check to `!characterId || characterId === 'new'` and removed the duplicate `/new` route, relying on the single `/:characterId` route to match both `new` and UUID values.
