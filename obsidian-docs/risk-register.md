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
**Status:** Largely mitigated (M9 complete)

**Current state:** Character creation uses guided wizard with seeded reference data. Race/class/subclass/background selected from dropdowns. Ability scores via standard array, point buy, or manual entry. Derived stats auto-calculated. Six-tab character sheet with Stats, Actions, Spells, Inventory, Features, Journal. Proficiency collection from race + class + background. Short/long rest mechanics. Campaign assignment. Character deletion with soft-delete. Spells tab fully functional: source-grouped boxes (per-class, race, feat), spell detail modal, preparation/known spell management modals, auto-calculated spell slots/DC/attack bonus. Spell selection step in creation wizard.

**Remaining gaps:**
- Multiclass spell slot edge cases may need manual override
- Some feats (e.g. Rune Shaper) may have data encoding that doesn't perfectly match rules text

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

### R010: Spell Effect Data Accuracy

**Category:** Data Quality
**Severity:** Medium — incorrect spell automation produces wrong combat outcomes
**Likelihood:** Medium — 294 definitions generated by AI, subtle errors are likely in some
**Status:** Open (M7/M8)

**Mitigations:**
- Human review of every spell definition before use in implementation
- `docs/spell-effect-review.md` summary table for efficient review (pattern category, delivery method, manual resolution flag, review notes)
- "REVIEW:" notes on any definition where the AI is uncertain about a field
- Cross-validation against 5e.tools structured metadata (conditionInflict, savingThrow, spellAttack, damageInflict)
- `requiresManualResolution` flag for complex spells — better to fall back to manual DM resolution than automate incorrectly
- Spell test harness in M11 validates each pattern category with representative spells before going live

**Risk areas:** Wrong upcast scaling (e.g., extra dice vs extra targets), wrong save type, missed secondary effects (e.g., Guiding Bolt also grants advantage on next attack), incorrect target count, missing half-on-save flag.

### R011: Character Builder Scope

**Category:** Feature / Scope
**Severity:** Low — core builder shipped, remaining items are enhancements
**Likelihood:** Low — M9 delivered the full creation wizard and character sheet
**Status:** Largely mitigated (M9 complete)

**Current state:** 6-step guided creation wizard with 226 races, 13 classes, 124 subclasses, 101 backgrounds. All ability score methods implemented. Multiclass support with PHB spell slot calculation. Pact Magic handled. Short/long rest mechanics. Background equipment and proficiency rendering with full coverage of 5e.tools data patterns. Campaign assignment and character deletion.

**Remaining risk areas:**
- Some exotic race/background combinations may have edge cases in proficiency data
- Feat spell data parsing covers 4 spell-granting feats (Magic Initiate, Strixhaven Initiate, Scion of the Outer Planes, Initiate of High Sorcery); additional homebrew feats may need parser updates

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
- `docs/monster-action-review.md` summary table for human review
- Progressive enhancement: CR 16+ monsters can be added later without system changes

**Risk areas:** Inconsistent 5e.tools markup across source books, multiattack descriptions that reference other actions by name (need parsing), recharge mechanics, spellcasting blocks with varying formats (innate vs prepared vs known), higher-CR monsters with multi-phase actions and aura effects.

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
