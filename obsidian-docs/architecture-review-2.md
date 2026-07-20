# Architecture Review — Second Pass

*Comprehensive review conducted 2026-07-20 from 6 independent perspectives. Reflects the codebase AFTER the first review's action plan was fully implemented.*

---

## What Improved Since First Review

| First Review Finding | Severity Then | Status Now |
|---|---|---|
| Silent exception swallowing (18+ `catch (Exception ignored) {}`) | Critical | **Resolved** — all replaced with `log.error()`, critical ops re-throw |
| CharacterService god object (1420 lines) | Critical | **Resolved** — extracted to 997L + CharacterMapper (138L) + CharacterJsonHelper (347L) |
| Zero automated tests | High | **Resolved** — 39 backend unit tests across 4 files |
| Short rest: only 1 hit die, no pact slot reset | High | **Resolved** — multi-dice UI, pact slot reset, feat resource reset |
| Concentration saves ignore CON proficiency | High | **Resolved** — `hasConSaveProficiency()` added to `CombatService.java:651` |
| No input validation on updates | Medium | **Resolved** — `@Valid` + Jakarta constraints on `CharacterUpdateRequest` and `CharacterCreateRequest` |
| No draft saving in creation wizard | High | **Resolved** — localStorage draft + `beforeunload` guard |
| CharacterCreateWizard.tsx (3,605 lines) | High | **Resolved** — split to 902L + 7 step components + types.ts + constants.ts |
| Untyped JSONB everywhere | High | **Partially resolved** — typed records for `LevelHistoryEntry`, `MulticlassEntry`, `HitDiceEntry`, `SpellSlotEntry`, `FeatResourceEntry` |
| No caching on reference data | High | **Resolved** — `@Cacheable` on 13 reference endpoints |
| No database indexes on FKs | High | **Resolved** — JPA `@Table(indexes)` on 5 FK columns + GIN index on `spells.classes` |
| Frontend logic duplication | High | **Resolved** — shared `utils/dndRules.ts` with `abilityMod`, `formatMod`, `safeJsonParse`, etc. |

**Overall: 12/12 action plan items completed. The codebase is substantially healthier.**

---

## Executive Summary — New Findings

| Theme | Flagged By | Severity |
|---|---|---|
| IDOR on `GET /api/characters/{id}` — no auth check | Senior Eng | Critical |
| Long rest doesn't reset short-rest feat resources | D&D Expert | High |
| Unconscious attack doesn't auto-crit on melee | D&D Expert | High |
| CharacterSheetPage.tsx still 2,103 lines | Senior Eng, New Eng | High |
| AsiModal.tsx still 903 lines | Senior Eng | Medium |
| CombatService duplicated damage application logic | Senior Eng | High |
| No connection pool tuning (default 10) | Architect | High |
| In-memory STOMP broker blocks horizontal scaling | Architect | High (unchanged) |
| No global React error boundary | PM | High |
| Zero ARIA attributes in frontend | PM | High |
| No class resource tracking (Ki, Action Surge, etc.) | D&D Expert, End User | High (unchanged) |
| Catch blocks in CharacterJsonHelper: 13 `catch → log + return null` | Senior Eng | Medium |
| No Flyway — using Hibernate `ddl-auto: update` | Architect, New Eng | Medium |
| Single spellcasting ability for multiclass casters | D&D Expert | Medium |

---

## 1. Code Quality & Maintainability

*Senior Engineer perspective*

### Critical: IDOR on Character GET Endpoint

`CharacterController.java:53-55` — `getCharacter(@PathVariable UUID characterId)` takes no `Authentication` parameter and calls `characterService.getCharacter(characterId)` which does no ownership check. **Any authenticated user can read any character by UUID.** All other endpoints (`updateCharacter`, `deleteCharacter`, `levelUp`, etc.) correctly validate ownership.

```java
@GetMapping("/{characterId}")
public ResponseEntity<CharacterResponse> getCharacter(@PathVariable UUID characterId) {
    return ResponseEntity.ok(characterService.getCharacter(characterId));
}
```

**Fix:** Add `Authentication authentication` parameter and pass `userId` to a version of `getCharacter` that validates ownership or campaign membership.

### High: CharacterSheetPage.tsx Still 2,103 Lines

This was flagged in the first review but not addressed. The file contains:
- 6 tab renderers (Stats, Actions, Spells, Inventory, Features, Journal) — each 100-300 lines
- Short rest and long rest handlers
- Level up flow orchestration
- Spell slot management
- Feat resource display

**Recommended split:** Extract each tab into its own component (`StatsTab.tsx`, `SpellsTab.tsx`, etc.), leaving the main page as orchestration + state management (~300 lines).

### High: Duplicated Damage Application in CombatService

`CombatService.java` has nearly identical temp HP absorption + HP reduction + death/dying logic in three places:
1. `rollAttack()` lines 125-177 — attack damage with temp HP
2. `applyDamage()` lines 218-253 — direct damage with temp HP
3. `setHp()` lines 316-337 — HP override with death state

The temp HP absorption block alone (check tempHp > 0, subtract from tempHp first, remainder hits real HP, check for 0 HP → dying/dead) is duplicated verbatim between `rollAttack` and `applyDamage`. Extract to a private `applyDamageToTarget(target, amount)` method.

### Medium: CharacterJsonHelper — 13 Catch Blocks Returning Null/Empty

`CharacterJsonHelper.java` has 13 `catch (Exception e) { log... return null/empty; }` blocks. While these now log (improvement from first review), returning null on JSON parse failure can cascade to silent data loss. For methods like `appendFeatures`, `updateHitDiceMap`, and `appendLevelHistory`, a parse failure means the level-up data is quietly dropped while the transaction commits.

**Recommendation:** Methods that mutate character state should throw (wrapped in `IllegalStateException`) rather than returning null. Methods that only read/parse can return defaults.

### Medium: AsiModal.tsx at 903 Lines

Manages ASI allocation, feat selection, feat spell picking, ability choices, and optional feature selection in one component. Would benefit from splitting feat selection into its own `FeatPicker.tsx`.

### Low: Inconsistent Error Handling Across Services

- `CharacterService`: wraps `Exception` in `IllegalStateException` (good)
- `CombatService.checkConcentration()` line 658: catches `Exception`, returns `false` — silently skips CON save check
- `CharacterJsonHelper`: catches and returns null/empty
- `FeatEffectResolver.applyFeat()` line 47: throws `Exception` in signature (overly broad)

---

## 2. Scalability & Performance

*Architect perspective*

### High: No Connection Pool Configuration

`application-dev.yml` has no HikariCP configuration. Spring Boot defaults to 10 connections. During an encounter with 5+ players, each `rollAttack` or `applyDamage` call loads the encounter + all participants + potentially character data — one long transaction per action. At 10 concurrent encounters, pool exhaustion is likely.

**Fix:** Add to `application-dev.yml`:
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
```

### High: In-Memory STOMP Broker (Unchanged)

`WebSocketConfig.java:20` still uses `config.enableSimpleBroker("/topic", "/queue")`. This stores all subscription state in JVM memory, blocking horizontal scaling. Not a problem at current scale (9 users), but becomes critical if deployment grows beyond a single instance.

### Medium: No Flyway Migration Management

`application-dev.yml` uses `hibernate.ddl-auto: update`. Hibernate's `update` mode:
- Never drops columns/tables (schema grows monotonically)
- Cannot handle complex migrations (column renames, data transforms)
- Is explicitly warned against for production by the Hibernate team

**Recommendation:** Add Flyway or Liquibase for versioned schema migrations.

### Medium: N+1 Still Present in Character Listing

`CharacterService.getMyCharacters()` at line 889 calls `findByUserIdAndIsActiveTrue()` then maps each through `characterMapper.toResponse()`. If `toResponse` accesses lazy-loaded associations (race, class, subclass, background), this creates N+1 queries. For a user with 5 characters, that's potentially 25+ queries instead of 1.

**Fix:** Add `@EntityGraph` or `JOIN FETCH` to the repository query.

### Positive: Caching Working Well

13 `@Cacheable` annotations on reference endpoints eliminate repeated DB reads for static data. This was a key recommendation from the first review and is properly implemented.

---

## 3. D&D 5e Rules Accuracy

*Subject Matter Expert perspective*

### High: Long Rest Doesn't Reset Short-Rest Resources

`CharacterSheetPage.tsx:159-161`:
```typescript
const resetResources = featResources.map(r =>
  r.resetOn === 'longRest' ? { ...r, currentUses: r.maxUses } : r
);
```

PHB p.186: "A long rest... At the end of a long rest, a character regains all lost hit points" and implicitly includes all benefits of a short rest. Resources with `resetOn === 'shortRest'` (e.g., Lucky's 3 Luck Points if coded as short-rest) are NOT restored on long rest with this code.

**Fix:** Change the filter to `r.resetOn === 'shortRest' || r.resetOn === 'longRest'` — matching the short rest handler at line 134.

### High: Unconscious Attacks Don't Auto-Crit on Melee

`CombatService.java:40-41`:
```java
if (targetDowned) {
    boolean isCrit = forceCrit;
```

PHB p.292: "Any attack that hits the creature is a critical hit if the attacker is within 5 feet of the creature." The code auto-hits (correct) but only crits if `forceCrit` is manually toggled by the DM. Since the system doesn't track melee vs ranged, it can't automatically distinguish — but default behavior should be auto-crit with an option to override for ranged attacks.

### Medium: Single Spellcasting Ability for Multiclass

`CharacterService.recalculateSpellSlots()` at lines 808-809 sets one `spellcastingAbility` for the entire character — whichever caster class is encountered first. PHB p.164: "Each [multiclass caster] uses its own ability modifier." A Cleric/Wizard should have separate WIS and INT save DCs, but the system stores only one `spellSaveDc` and one `spellAttackBonus`.

**Impact:** Uncommon (most multiclass casters share the same ability or players know their secondary DC). Fixing requires storing per-class DCs, which is a data model change.

### Medium: Ability Score Cap Always 20

`CharacterService.applyAbilityIncrease()` at line 731: `Math.min(20, ...)`. Barbarian "Primal Champion" (level 20) raises STR/CON cap to 24. Currently impossible to represent.

### Low: Condition Duration Tracking Simplified

`CombatService.expireConditions()` at line 779: `(encounter.getRoundNumber() - c.appliedRound) >= c.duration`. Conditions expire at the START of the affected creature's turn, not at a round count threshold. The current implementation is close but doesn't account for initiative order within a round.

### Positive: Rules Accuracy Improvements

- **Short rest:** Multi-dice spending with per-class hit die types — correct (PHB p.186)
- **Pact slot reset on short rest:** Correct (PHB p.107)
- **Concentration saves:** DC = max(10, damage/2) with CON save proficiency — correct (PHB p.203)
- **Death saves:** Nat 1 = 2 failures, nat 20 = revive at 1 HP — correct (PHB p.197)
- **Massive damage:** Excess damage >= max HP from 0 = instant death — correct (PHB p.197)
- **Spell slot calculation:** Full/half/third/artificer/pact tables all verified correct against PHB p.165
- **Ability modifier:** `Math.floorDiv(score - 10, 2)` — correct (PHB p.13)
- **Proficiency bonus:** 2 at 1-4, 3 at 5-8, 4 at 9-12, 5 at 13-16, 6 at 17+ — correct (PHB p.15)

---

## 4. User Experience & Features

*Product Manager perspective*

### High: No Global React Error Boundary

No `ErrorBoundary` component exists anywhere in the frontend. An unhandled JS exception in any component crashes the entire app to a white screen. This is especially dangerous during combat where a bad render in the initiative list or condition display could force a page reload, losing unsaved state.

### High: Zero ARIA Attributes

`grep -r "aria-\|role=" frontend/src/pages/ --include="*.tsx" | wc -l` returns **0**. The entire frontend is inaccessible to screen readers. While this may not affect the primary user base (9 D&D players), it's a basic accessibility gap.

### Medium: No DM View of Player Character Sheets

DMs cannot view player character sheets from the campaign management page. This is needed for encounter prep — calculating challenge ratings, knowing player ACs/saves, etc.

### Medium: No Inline HP Editing Outside Combat

Characters can only modify HP through short/long rests or encounters. There's no way to take fall damage, environmental damage, or other out-of-combat HP changes without going through the update API manually.

### Positive: Major UX Improvements

- `window.prompt()` for temp HP: **Removed** — no longer present anywhere in the frontend
- Draft saving: **Added** — localStorage + `beforeunload` guard on creation wizard
- Character creation wizard: Fully modular 7-step flow with step components

---

## 5. Session Automation

*End User perspective*

### High: No Class Resource Tracking (Unchanged)

Ki Points, Action Surge, Channel Divinity, Bardic Inspiration, Wild Shape, Rage, Sorcery Points — none are tracked. The feat resource system (`FeatResourceEntry` with `maxUses`, `currentUses`, `resetOn`) already provides the exact infrastructure needed. Extending it to class resources would be a natural fit.

### High: Monster Actions Require Manual Entry Every Attack

The DM must type attack bonus, damage dice, and damage type for every monster attack. Pre-populated attack profiles from monster stat blocks would save 2-5 minutes per combat per session.

### Medium: No Encounter-to-Character-Sheet HP Sync

When an encounter ends, spent spell slots, HP changes, and hit dice spent are not written back to the character sheet. Players must manually update their sheets after combat.

### Positive: What Works Well

- Multi-dice short rest with per-class hit dice — smooth UX
- Death save automation — nat 1/20 rules are correct and clear in combat log
- Spell slot tracking with clickable pips — intuitive
- Concentration auto-check on damage — prevents forgotten saves
- Session code join — one-step encounter onboarding

---

## 6. Onboarding & Documentation

*New Engineer perspective*

### Positive: Documentation Is Excellent

- `obsidian-docs/` covers architecture, API reference, database schema, 91 decisions-log entries, risk register, feature roadmap, WebSocket protocol, auth flow, deployment, and troubleshooting
- README gets a new developer running in under 5 minutes
- Architecture review (first pass) provides comprehensive context

### Medium: Stale First-Review References

`obsidian-docs/architecture-review.md` Section 6 "First-Week Survival Guide" still says:
- "The two biggest files: CharacterCreateWizard.tsx (3605L) and CharacterService.java (1420L)" — both have been split
- "There are no tests — everything is manually tested" — 39 tests now exist

The survival guide should be updated to reflect current file sizes and test coverage.

### Medium: JSONB Field Behavior Still Not Documented

The `@JsonRawValue` deserialization trap (reference entities return pre-parsed objects, PlayerCharacter fields return strings needing `JSON.parse()`) is mentioned in the first review but not in `database-schema.md` or a developer guide. New engineers will hit this trap.

### Low: Documentation Spread Across Multiple Locations

README, `obsidian-docs/`, `docs/`, `PROJECT_DOCUMENTATION.md`, `CLAUDE_CODE_BRIEF.md` — unclear which is canonical. The latter two are local-only files per `.claude/CLAUDE.md`, but this isn't documented anywhere a new engineer would see it.

---

## Prioritized Action Plan

### Week 1: Fix Critical + High Issues

| Day | Action | Effort |
|---|---|---|
| 1 | **Fix IDOR on `GET /api/characters/{id}`**: add auth check, verify ownership or campaign membership | 30 min |
| 1 | **Fix long rest feat resource reset**: change filter to include `shortRest` resources | 5 min |
| 1 | **Fix unconscious melee auto-crit**: default `isCrit = true` for downed targets, add `isRanged` field to `AttackRollRequest` to opt out | 1 hour |
| 2 | **Add global React error boundary**: wrap `<App>` in `ErrorBoundary` with "reload" button, don't lose router state | 1 hour |
| 2 | **Extract CombatService damage helper**: consolidate temp HP + HP reduction + dying logic into `applyDamageToTarget()` | 2 hours |
| 3-4 | **Split CharacterSheetPage.tsx**: extract 6 tab components (`StatsTab`, `SpellsTab`, etc.) | 4-6 hours |
| 5 | **Configure HikariCP pool**: 20 max connections, 5 minimum idle | 15 min |

### Week 2: Structural Improvements

| Day | Action | Effort |
|---|---|---|
| 6 | Add JPA fetch joins / `@EntityGraph` for character listing queries | 2 hours |
| 7 | Add Flyway with initial migration from current schema | 3 hours |
| 8 | Update `architecture-review.md` survival guide with current file sizes and test counts | 30 min |
| 8 | Document `@JsonRawValue` behavior in `database-schema.md` | 30 min |
| 9 | Add basic ARIA attributes (labels, roles) to key interactive elements | 3 hours |
| 10 | Split AsiModal.tsx — extract FeatPicker component | 2 hours |

### Next Sprint

- Build saved attack profiles for monster encounters
- Add class resource tracking (extend feat resource system)
- Add encounter-to-character-sheet HP/slot sync on encounter end
- Replace SimpleBroker with RabbitMQ/Redis for horizontal scaling
- Add per-class spellcasting ability for multiclass casters
- Add DM view of player character sheets
