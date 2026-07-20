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
docker exec tabletophelper-db pg_isready
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

**Cause:** Usually a JavaScript runtime error that prevents React from rendering. Two known causes:

1. **Type-only imports without `type` keyword:** Importing TypeScript types with `import { ... }` instead of `import type { ... }`. Vite 8's Rolldown bundler treats these as runtime values, which resolve to `undefined` and crash React.

2. **`global is not defined` from sockjs-client:** The `sockjs-client` library references Node.js's `global` variable. Vite 8's Rolldown bundler doesn't auto-shim this (esbuild did). The polyfill `globalThis.global = globalThis` in `index.html` fixes it.

**Fix:** Check the browser console (F12 → Console).

For `MISSING_EXPORT` or undefined component errors:
```typescript
// Bad — fails with Vite 8 / Rolldown
import { MyInterface } from '../types/foo';
import { useState, FormEvent } from 'react';

// Good
import type { MyInterface } from '../types/foo';
import { useState, type FormEvent } from 'react';
```

For `Uncaught ReferenceError: global is not defined`:
Add before the app script in `index.html`:
```html
<script>globalThis.global = globalThis;</script>
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
docker exec tabletophelper-db psql -U tabletophelper -d tabletophelper -c "\dt"

# Count rows in each table
docker exec tabletophelper-db psql -U tabletophelper -d tabletophelper -c "
  SELECT 'users' AS tbl, COUNT(*) FROM users
  UNION ALL SELECT 'campaigns', COUNT(*) FROM campaigns
  UNION ALL SELECT 'campaign_members', COUNT(*) FROM campaign_members
  UNION ALL SELECT 'player_characters', COUNT(*) FROM player_characters;
"

# View a specific user
docker exec tabletophelper-db psql -U tabletophelper -d tabletophelper -c "SELECT id, username, email, display_name FROM users;"
```

### Data survives container restarts

PostgreSQL data is stored in a Docker named volume (`tabletophelper-data`), not in the container filesystem. Stopping, restarting, or even removing the container preserves data. Only `docker compose down -v` (the `-v` flag) deletes volumes.

```bash
# Check the volume exists
docker volume ls | grep tabletophelper

# Inspect it
docker volume inspect dmscreen_tabletophelper-data
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
docker exec tabletophelper-db psql -U tabletophelper -d tabletophelper -c "SELECT username, email FROM users;"
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

## Known Issues

### KI-001: Class skill selections not reset when race changes

**Impact:** Low — duplicate skills don't cause functional issues but waste a proficiency pick.
**Status:** Known, fix planned.

**Description:** If a player picks class skill proficiencies, then changes their race to one that grants some of those same skills, the already-selected class skills that now conflict with the race remain selected. The UI shows them crossed-out (from race) but they're still in the `selectedClassSkills` array and would be sent to the server as duplicates.

**Recreation:**
1. Start character creation.
2. Pick a race that grants no skill proficiencies (e.g., Dwarf).
3. Pick a class (e.g., Rogue) and select skill proficiencies (e.g., Stealth, Perception).
4. Go back and change race to one that grants Stealth (e.g., Wood Elf).
5. Notice Stealth is now crossed-out in the class skill picker but still selected.

---

### KI-002: Step index can become invalid when spells step visibility changes

**Impact:** Low — user can navigate back. No data loss.
**Status:** Known, fix planned.

**Description:** If a user is on the Spells step, then goes back and changes class from a spellcaster to a non-caster, `showSpellsStep` becomes false, the steps array shrinks by 1, and the current step index points to Review instead of the expected step.

**Recreation:**
1. Create a Wizard character (step 6 = Spells visible).
2. Navigate to the Spells step.
3. Go back to the Class step and change class to Fighter (no spellcasting).
4. The step indicator now shows you on Review instead of Class.

---

### KI-003: Exit prerequisite check only validates primary class for 3+ class multiclass

**Impact:** Theoretical only. No practical impact during character creation or level-up.
**Status:** Documented as theoretical edge case. No fix planned.

**Description:** When adding a third class via multiclass, only the primary class's exit prerequisites are checked. PHB requires meeting prerequisites for ALL current classes. However, this is a theoretical-only issue: since ability scores only increase (never decrease) during normal play, any prerequisite previously met will always remain met.

**Recreation:** Only triggerable if ability scores could decrease, which doesn't happen in standard PHB character building.

---

### KI-004: Subclass race condition for level-1 subclass classes

**Impact:** Very low — requires artificial slow network conditions.
**Status:** Known potential enhancement. Could add a loading gate for subclass fetch.

**Description:** Cleric, Sorcerer, and Warlock require subclass selection at level 1. The `canAdvance()` check skips subclass validation if the async subclass fetch hasn't completed. On a very slow connection, a user could advance past the Class step without selecting a subclass.

**Recreation:** Would require network throttling (e.g., Chrome DevTools slow 3G) so the subclass fetch takes several seconds, then rapidly clicking "Next" before subclasses load.

---

### KI-005: Forward reference risk in memo ordering (FIXED)

**Impact:** Critical if triggered — blank white screen.
**Status:** Fixed. Note for future refactoring: memo ordering matters in CharacterCreateWizard.tsx.

**Description:** The `raceSkills` and `bgSkillConflicts` memos were initially placed before their dependencies (`resolvedRaceChoices`, `resolvedBgProfs`), causing a TDZ (Temporal Dead Zone) crash. This was fixed by moving them after their dependencies. The pattern is fragile and could recur if memos are reordered during refactoring.

**Recreation (of the broken state):** Move `raceSkills` useMemo before `resolvedRaceChoices` useMemo in `CharacterCreateWizard.tsx`. The component will crash with a blank white screen on mount.

---

### KI-006: Client-sent HP not validated at level 1

**Impact:** Low — requires crafted API request. Normal UI always sends correct HP.
**Status:** Known. Server-side HP validation planned for a future security hardening pass.

**Description:** For single-class level 1 characters, the server trusts the client-sent `hpMax` value. A crafted API request could send an arbitrary HP value.

**Recreation:** Send a POST to `/api/characters` with `hpMax: 999` and `level: 1` for a single-class character. The character is created with 999 HP.

---

### KI-007: Ability scores can be null in CharacterCreateRequest

**Impact:** Low — normal UI always sends ability scores. Affects only crafted API requests.
**Status:** Known. Validation enhancement planned.

**Description:** The six ability score fields on `CharacterCreateRequest` have `@Min(1) @Max(30)` but no `@NotNull`. A request with null ability scores passes validation and creates a character with null scores.

**Recreation:** Send a POST to `/api/characters` without any ability score fields. The character is created with null ability scores.

---

### KI-008: Silent exception swallowing in CharacterService

**Impact:** Medium — data corruption risk on malformed state. Normal operation is unaffected.
**Status:** Known. Error logging was added to `LevelUpCalculator` catch blocks. `CharacterService` catch blocks still need logging.

**Description:** 12+ methods in `CharacterService` have `catch (Exception ignored) {}` blocks: `removeFeatures`, `appendFeatures`, `updateHitDiceMap`, `updateMulticlassEntries`, `recalculateSpellSlots`, `appendLevelHistory`, `recordAsiInHistory`, and more. If any of these operations fail (e.g., corrupt JSON in level history), the character ends up in an inconsistent state (e.g., level incremented but features not added, or HP changed but hit dice map not updated).

**Recreation:** Corrupt a character's `levelHistory` JSON directly in the database, then trigger a level-up. The level increments but features may not be added correctly.

---

### KI-009: Stale subclass reference on class level reduction

**Impact:** Low — server validates subclass level requirements. Stale UI state only.
**Status:** Known, no fix planned. Server is the source of truth.

**Description:** When a multiclass entry's class level is reduced below its `subclassLevel`, the subclass picker disappears but the stale subclass reference remains in `classEntries`. The data is sent to the server but the server validates subclass requirements independently.

**Recreation:**
1. Start character creation at level 6+.
2. Add a multiclass class (e.g., Fighter at level 3).
3. Select a subclass for Fighter (e.g., Champion, available at level 3).
4. Reduce the Fighter's class level to 2 (below subclass level 3).
5. The subclass picker disappears but the stale subclass reference remains in state.

---

## Feat Ability Choice Not Showing in AsiModal

**Status:** Fixed (2026-07-20)

**Description:** When selecting a feat with an optional ability score increase (e.g., Fey Teleportation +1 INT or CHA), the ability choice picker did not appear in the AsiModal. The feat was applied without the stat bonus.

**Root Cause:** The `Feat` entity uses `@JsonRawValue` on JSONB fields. Jackson outputs these as raw JSON, so the browser JSON parser converts them to native JS objects before frontend code runs. `parseAbilityScoreIncrease()` called `JSON.parse()` on the already-parsed array, which coerced it to `"[object Object]"` and threw SyntaxError. The catch block silently returned `null`, hiding the choice UI.

**Fix:** Added `typeof` guard: `typeof feat.abilityScoreIncrease === 'string' ? JSON.parse(...) : feat.abilityScoreIncrease`. Same fix applied to `parseFeatEffects()`.

**Key Lesson:** All `@JsonRawValue` JSONB fields arrive as pre-parsed JS objects — always check `typeof === 'string'` before `JSON.parse()`.

---

## Spell Casting Fails with "Failed to cast spell" (M11)

**Status:** Fixed (2026-07-20)

**Description:** All spell casting in encounters failed silently with "Failed to cast spell". Additionally, spell slot level selection was greyed out for leveled spells.

**Root Causes (two bugs):**

1. **CHECK constraint missing SPELL_CAST:** The `combat_logs` table had a Hibernate-generated CHECK constraint on `action_type` that did not include `SPELL_CAST`. Every cast-spell call that tried to log the action threw `DataIntegrityViolationException`. Fixed by Flyway V3 migration that drops and recreates the constraint with all 16 action types.

2. **Spell slot format mismatch:** `PlayerCharacter.spellSlots` uses `{used, total}` format but `CombatService` expects `{remaining, max}`. When a character joins an encounter, `EncounterService.addPlayerParticipant()` was copying the raw JSON. `slot.getOrDefault("remaining", 0)` returned 0, making the frontend show all slots as empty/greyed-out. Fixed by adding `convertSpellSlotsFormat()` that converts `{used, total}` → `{remaining: total-used, max: total}` at copy time.

---

## Race Spells All Show as Cantrips (Level 0)

**Status:** Fixed (2026-07-20)

**Description:** 46 races had non-cantrip spells (e.g., Enlarge/Reduce) stored with level 0 in their `additional_spells` JSON.

**Root Cause:** `RaceSeeder.buildSpellLevelLookup()` tried to read `data/5etools/spells.json` which doesn't exist — spells are split across multiple source-book files (`spells-phb.json`, `spells-xge.json`, etc.). The lookup map was empty, so all race spells defaulted to level 0.

**Fix:** Changed `buildSpellLevelLookup()` to query `SpellRepository.findAll()` (database) instead of the nonexistent JSON file. Added `fixRaceSpellLevels()` method that runs on every startup and corrects any level-0 non-cantrip spells by cross-referencing the database.

---

## Blank Spell Line in Feat Spell Section

**Status:** Fixed (2026-07-20)

**Description:** Feat spells added via the AsiModal (level-up) showed as blank lines in the Spells tab. Clicking a blank line opened an unrelated spell detail (e.g., "Horrid Wilting").

**Root Cause:** `FeatEffectResolver.applyFeatSpells()` stored spell entries with only `{id, source}`, missing `name` and `level`. The Spells tab rendered `spell.name` as undefined (blank row). `viewSpellDetail("")` searched with no name filter, returning the first API result.

**Fix:** Backend now looks up spells by ID from the repository and stores full entries with `name`, `level`, `source`, `usesPerLongRest`/`atWill`. Frontend filters out nameless entries and guards `viewSpellDetail` against empty names.

---

## Healing Spells Return Zero Healing

**Status:** Fixed (2026-07-21)

**Description:** All auto-resolved healing spells (Cure Wounds, Healing Word, Mass Healing Word, Prayer of Healing) returned `healing: null` / 0 HP healed despite the spell being cast successfully.

**Root Cause:** `SpellResolverEngine.resolveHealing()` read `healingNode.path("healDice")` but all spell effect templates use the key `healingDice` (and Prayer of Healing uses `dice`). The field name mismatch caused every healing dice lookup to return null.

**Fix:** Changed to read `healingDice` with a `dice` fallback. Updated the corresponding test fixture to use `healingDice`.

---

## Magic Stone Crashes with "Invalid dice expression: 1d6+SPELL_MOD"

**Status:** Fixed (2026-07-21)

**Description:** Casting Magic Stone threw an error because the dice roller received the raw placeholder string `1d6+SPELL_MOD` instead of a resolved expression like `1d6+5`.

**Root Cause:** `SpellResolverEngine.resolveDamageDice()` returned the raw `damageDice` string from the spell template without replacing the `SPELL_MOD` placeholder with the caster's spellcasting ability modifier.

**Fix:** After cantrip/upcast scaling, the method now replaces `SPELL_MOD` with the computed spellcasting modifier (spell attack bonus minus proficiency bonus derived from character level).

---

## Spells with Damage in Non-First Effect Deal No Damage

**Status:** Fixed (2026-07-21)

**Description:** Lightning Lure, Absorb Elements, Armor of Agathys, Ensnaring Strike, Zephyr Strike, and other spells with the damage effect listed as the 2nd+ entry in the `effects` array dealt 0 damage on a hit/failed save.

**Root Cause:** `resolveDamageDice()` only checked `effects.get(0)` for `damageDice`. Spells whose first effect is a non-damage entry (pull, buff, control) had their damage in a later effect, which was never read.

**Fix:** Introduced `findDamageEffect()` that iterates all effects to find the first with a `damageDice` field. Damage type is now also extracted from this same effect node.

---

## Pact Slot Use/Restore Endpoints Fail for Warlocks

**Status:** Fixed (2026-07-21)

**Description:** The `POST /spell-slot/use` and `POST /spell-slot/restore` endpoints failed for Warlock participants with "No level N spell slots remaining" or "Participant has no level N spell slots", even when pact slots had remaining uses.

**Root Cause:** Both endpoints looked up spell slots by key `"N"` (e.g., `"5"`), but Warlock pact slots are stored with the key `"pact_N"` (e.g., `"pact_5"`). The key mismatch caused a null lookup.

**Fix:** Both `useSpellSlot()` and `restoreSpellSlot()` now fall back to checking `"pact_N"` when the regular `"N"` key is not found.
