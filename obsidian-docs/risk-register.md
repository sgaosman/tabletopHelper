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
**Likelihood:** Low — core functionality now in place
**Status:** Largely mitigated (M9, M10, M20, M21, M24/Tourmaline Theme complete)

**Current state:** Character creation uses guided 7-step wizard (split into modular step components, with its state in one reducer since R8) with reference data loaded from the content files, localStorage draft saving, and beforeunload guard. Race/class/subclass/background selected from dropdowns. Ability scores via standard array, point buy, or manual entry. Derived stats auto-calculated. Six-tab character sheet with Stats, Actions, Spells, Inventory, Features, Journal. Proficiency collection from race + class + background. Short rest with multi-dice spending and warlock pact slot reset. Long rest mechanics. Campaign assignment. Character deletion with soft-delete. Spells tab fully functional: source-grouped boxes (per-class, race, feat), spell detail modal, preparation/known spell management modals, auto-calculated spell slots/DC/attack bonus. Wizard spellbook management (add/remove, prepare from spellbook only). Character leveling with level up/down, multiclass support, PHB prerequisite validation, ASI/feat/subclass choices, deterministic rollback via levelHistory. Feat automation with full mechanical effects (ability scores, proficiencies, resistances, speed, resources, spells). Since the refactor phase (R1–R8, D150) the server owns the rules: it works out every Derived Stat (D167), takes rests and level changes as commands (D158), tracks Pending Choices (D159), checks feat prerequisites on players' choices (D158), and reads spell slot tables and pool level tables from the content data (D169). Class resources such as Ki and Action Surge are resource pools (D125, D127).

**Remaining gaps:**
- Some feats (e.g. Rune Shaper) may have data encoding that doesn't perfectly match rules text
- Invocations, pact boons, Metamagic and maneuvers aren't modelled yet; a background's and a class's starting equipment come later (D150)

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

**Update (2026-11-02, R3):** D156 closed both accepted gaps. Access tokens now last 15 minutes and live only in the frontend's memory. Refresh tokens are random values in an HttpOnly, SameSite=Strict cookie, stored only as hashes, replaced on every use and revocable: logout revokes the login's token family, and so does reusing a replaced token. `JWT_SECRET` has no default outside dev and must be at least 32 bytes. Still open: an access token can't be revoked before it expires, and the Secure cookie needs HTTPS, which waits for the Hosting step (D154).

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

### R010: Spell Effect Data Accuracy

**Category:** Data Quality
**Severity:** Medium — incorrect spell automation produces wrong combat outcomes
**Likelihood:** Medium — 294 definitions were generated in bulk, so subtle errors are likely in some
**Status:** Open (M7/M8)

**Mitigations:**
- Human review of every spell definition before use in implementation
- `obsidian-docs/archive/spell-effect-review.md` summary table for efficient review (pattern category, delivery method, manual resolution flag, review notes)
- "REVIEW:" notes on any definition with an uncertain field
- Cross-validation against 5e.tools structured metadata (conditionInflict, savingThrow, spellAttack, damageInflict)
- `requiresManualResolution` flag for complex spells — better to fall back to manual DM resolution than automate incorrectly
- Spell test harness in M11 validates each pattern category with representative spells before going live

**Risk areas:** Wrong upcast scaling (e.g., extra dice vs extra targets), wrong save type, missed secondary effects (e.g., Guiding Bolt also grants advantage on next attack), incorrect target count, missing half-on-save flag.

### R011: Character Builder Scope

**Category:** Feature / Scope
**Severity:** Low — core builder shipped, remaining items are enhancements
**Likelihood:** Low — M9/M10/M20/M21/M24/Tourmaline Theme delivered the full creation wizard, leveling, feat automation, and spellbook management
**Status:** Largely mitigated

**Current state:** 7-step guided creation wizard (split into modular step components with draft saving) with 226 races, 13 classes, 124 subclasses, 101 backgrounds. All ability score methods implemented. Multiclass support with PHB spell slot calculation and prerequisite validation. Pact Magic handled. Short rest with multi-dice spending and warlock pact slot reset. Long rest mechanics. Background equipment and proficiency rendering. Campaign assignment and character deletion. Level up/down with multiclass support, ASI/feat/subclass choices, and deterministic rollback. Feat effect automation (29 feats with structured effects). Wizard spellbook management. 1/3 caster support (Eldritch Knight, Arcane Trickster), marked as third casters in the content data since R8 (D167). Since R8 the wizard keeps its state in one reducer, one spell picker serves the wizard and the sheet, and feats are typed once on the server, which checks their prerequisites (D158, D171).

**Remaining risk areas:**
- Some exotic race/background combinations may have edge cases in proficiency data
- Feat data is typed once on the server and the frontend no longer parses feat spells or prerequisites; the feats loader fails on an unknown prerequisite key rather than letting it through (D158, D171)

### R012: Monster Action Parsing

**Category:** Data Quality
**Severity:** Medium — errors in ~1,300–1,600 definitions could produce wrong combat outcomes at scale
**Likelihood:** Medium — 5e.tools monster action text varies in format; large volume increases error surface
**Status:** Open (M7/M12)

**Mitigations:**
- Scope: all CR 0–10 (~1,200–1,500), all legendary/lair monsters at any CR (~60–80), CR 11–15 as secondary (~200–300). Monsters above CR 15 without legendary features use manual tools.
- Most CR 0–5 monsters have simple action profiles (1–3 attacks) that are fast and reliable to extract
- Fallback to manual damage/condition tools for any monster without structured action data (existing functionality, already tested)
- "REVIEW:" notes on any monster where structured extraction is uncertain
- `automatable: false` flag on individual actions that are too contextual to automate (e.g., "Detect" — makes a Perception check)
- `obsidian-docs/archive/monster-action-review.md` summary table for human review
- Progressive enhancement: CR 16+ monsters can be added later without system changes

**Risk areas:** Inconsistent 5e.tools markup across source books, multiattack descriptions that reference other actions by name (need parsing), recharge mechanics, spellcasting blocks with varying formats (innate vs prepared vs known), higher-CR monsters with multi-phase actions and aura effects.

### R013: Silent Exception Swallowing in CharacterService

**Category:** Reliability
**Severity:** Critical — character data silently corrupted with no error logged
**Likelihood:** High — 18+ `catch (Exception ignored) {}` blocks in the most-used service
**Status:** Resolved (M22 — Architecture Review Action Plan)

**Resolution:** All 18 silent catch blocks replaced with `log.error()` calls. Critical operations (`recalculateSpellSlots`, `appendLevelHistory`) now re-throw after logging so the transaction rolls back on failure.

### R014: No Automated Tests

**Category:** Quality
**Severity:** High — regressions in level up/down, spell slots, feat effects go undetected
**Likelihood:** High — no tests existed anywhere in the project
**Status:** Resolved (M25 — Comprehensive Testing Suite)

**Resolution:** 330 tests (220 backend + 110 frontend) added covering all services, utilities, and domain logic. See [[feature-roadmap#Milestone 25: Comprehensive Testing Suite]] for full breakdown.

**Remaining:** No frontend tests (React Testing Library). No integration tests hitting a real database. No end-to-end tests.

**Update (2026-11-02, R2):** frontend component tests now exist (343 Vitest tests, with MSW in strict mode), and `./gradlew integrationTest` runs 40 tests against a real PostgreSQL through Testcontainers (D172). End-to-end browser tests are still missing.

### R015: IDOR on Character GET Endpoint

**Category:** Security
**Severity:** Critical — any authenticated user could read any character by UUID
**Likelihood:** Low — UUIDs are hard to guess, but URL sharing or browser history could leak them
**Status:** Resolved (2026-07-20)

**Resolution:** Added ownership and campaign membership validation to `GET /api/characters/{id}`. Users can now only view characters they own or that belong to a campaign they're a member of.

**Update (2026-11-02, R3):** reading a character is now limited to its owner and the DM of its campaign; other campaign members get 403 (D155).

### R016: Reusable Placeholder Secret in .env.example

**Category:** Security
**Severity:** High — a `JWT_SECRET` left as the placeholder is public: anyone who knows it can sign an access token for any user whose ID they know, and every campaign member can see the DM's ID
**Likelihood:** Low — the deployment steps have the operator generate a secret, and the group is small and trusted
**Status:** Open (raised 2026-11-03)

**The gap:** `.env.example` is committed, and the first deployment copies it to `.env` (`cp .env.example .env`, see [[deployment-guide]]). Its `JWT_SECRET` placeholder, `CHANGE_ME_generate_with_openssl_rand_base64_64`, is 46 bytes, longer than the 32-byte minimum the backend enforces, so a deployment that leaves it unchanged starts normally with a secret that anyone who can read the repository knows. Access tokens are checked by signature alone, with no database lookup, so a token signed with that secret is accepted for whatever user ID it names.

**Mitigations:**
- `.env.example` states the rule beside the value: at least 32 bytes, 64 or more for HS512, generated with `openssl rand -base64 64` (R5 follow-ups, 2026-11-03)
- The deployment guide has the operator generate the secret before the first deploy
- The backend refuses to start when `JWT_SECRET` is missing or shorter than 32 bytes, and `docker-compose.prod.yml` refuses to start when it is unset (D156)

**Not mitigated:** nothing detects the placeholder itself, which passes both the length check and compose's "is set" check. `POSTGRES_PASSWORD`'s placeholder can be reused the same way, though the compose file publishes no database port.

## Resolved Risks

### R-RESOLVED-001: Vite 8 Build Failures with Type Imports

**Category:** Tooling
**Resolved:** 2026-07-17

**Problem:** Vite 8 uses Rolldown bundler which strips type-only exports at build time. Importing TypeScript interfaces with regular `import { ... }` caused `MISSING_EXPORT` errors in production builds and blank pages in dev.

**Resolution:** Use `import type { ... }` for all type-only imports. Applied to `AuthContext.tsx` and `authApi.ts`.

### R-RESOLVED-002: Stale JWT Causes Blank Pages

**Category:** UX / Security
**Resolved:** 2026-07-18

**Problem:** When a user's 1-hour access token expired, all API calls returned 403. The Axios interceptor only caught 401 responses, so expired tokens were not refreshed — pages showed blank or empty results with no feedback. Users had to manually log out and back in.

**Resolution:** Extended the Axios response interceptor to catch both 401 and 403, with a `hadToken` guard so only requests that actually carried a Bearer token trigger the refresh flow. Concurrent requests are queued during refresh. If the refresh token is also expired, localStorage is cleared and the user is redirected to login.

**Superseded in R3 (D155, D156):** Spring now answers a missing or expired token with 401, and the interceptor refreshes on 401 only, since a 403 means "not allowed". The refresh uses the HttpOnly `refresh_token` cookie, and nothing is kept in localStorage.

### R-RESOLVED-003: sockjs-client `global` Crash on Vite 8

**Category:** Tooling
**Resolved:** 2026-07-18

**Problem:** `sockjs-client` references the Node.js `global` variable. After a Vite dependency cache rebuild, Rolldown no longer auto-shimmed it (esbuild did). The app crashed on load with `ReferenceError: global is not defined`.

**Resolution:** Added `<script>globalThis.global = globalThis;</script>` to `index.html` before the app script.

### R-RESOLVED-004: Character Creation UUID Parse Error

**Category:** Bug
**Resolved:** 2026-07-17

**Problem:** Creating a new character at `/player/characters/new` failed with "Invalid UUID string: undefined". The route `/player/characters/new` didn't have a `:characterId` param, so `useParams()` returned `undefined`. The `isNew` check (`characterId === 'new'`) evaluated to `false`, causing the save handler to call `PUT /api/characters/undefined`.

**Resolution:** Changed `isNew` check to `!characterId || characterId === 'new'` and removed the duplicate `/new` route, relying on the single `/:characterId` route to match both `new` and UUID values.
