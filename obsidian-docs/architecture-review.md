# Architecture Review

*Comprehensive review conducted 2026-07-20 from 6 independent perspectives. Scaling target: 1,000 concurrent campaigns, 5,000 users.*

---

## Executive Summary

TabletopHelper is a genuinely impressive D&D 5e character and campaign management tool. The character creation wizard — with 226 races, all PHB classes/subclasses, point buy, multiclass support, feat automation, and wizard spellbooks — is best-in-class for a project at this stage. The 5etools data integration provides essentially 100% data completeness. The encounter system has solid real-time WebSocket sync, and death save automation is rules-accurate.

**Six independent reviewers converged on the same core issues:**

| Theme | Flagged By | Severity |
|---|---|---|
| Silent exception swallowing (18+ `catch (Exception ignored) {}`) | Senior Eng, New Eng | Critical |
| CharacterService god object (1420 lines, 10 dependencies) | Senior Eng, New Eng | Critical |
| No automated tests anywhere | Senior Eng, New Eng | High |
| Short rest mechanics are incorrect/incomplete | D&D Expert, End User | High |
| No class resource tracking (Ki, Action Surge, etc.) | D&D Expert, End User, PM | High |
| In-memory STOMP broker blocks horizontal scaling | Architect | Critical |
| No DB connection pool tuning (default 10 connections) | Architect | Critical |
| Monster actions require manual entry every attack | PM, End User | High |
| CharacterCreateWizard.tsx at 3605 lines with no draft saving | Senior Eng, New Eng, PM | High |
| Untyped JSONB everywhere (`Map<String, Object>`, `String` fields) | Senior Eng, New Eng | High |

---

## 1. Code Quality & Maintainability

*Senior Engineer + New Engineer perspectives*

### Critical: Silent Exception Swallowing

18+ `catch (Exception ignored) {}` blocks in `CharacterService.java` (lines 180, 224, 773, 783, 801, 809, 839, 890, 910, 923, 945, 991, 1016, 1063, 1111, 1137, 1163, 1316). These protect operations *inside transactions* — the transaction commits successfully but the character is left in an inconsistent state.

**Worst case:** `recalculateSpellSlots` (line 948-992) silently fails. After a multiclass level-up, a character could have stale spell slot counts forever with no error logged and no way to detect the problem.

### Critical: CharacterService God Object

1420 lines, 10 repository dependencies. Handles character creation (290-line method), updates, leveling up/down, ASI/feat application, spell slot recalculation, hit dice management, multiclass management, level history tracking, proficiency grants, and response mapping — all in one class.

**Recommended split:** LevelUpService, CharacterJsonHelper, SpellSlotService, CharacterMapper.

### High: Zero Automated Tests

No JUnit tests, no React Testing Library tests, no integration tests anywhere. The most dangerous untested code paths:

- `levelUp`/`levelDown` — 8+ side effects in sequence; ordering bugs silently corrupt state
- `FeatEffectResolver.applyFeat`/`reverseFeatEffects` — reversal must be the exact inverse
- `SpellSlotCalculator.calculateSlots` — multiclass rounding is notoriously tricky
- `MulticlassValidator` prerequisite checking

### High: Pervasive Untyped JSONB

23 JSONB columns on `PlayerCharacter`, all stored as Java `String` fields. The entire backend operates on `Map<String, Object>` (113 occurrences). No typed records for level history entries, multiclass entries, hit dice maps, or spell entries. Schema drift is invisible — the compiler cannot catch field name mismatches.

### High: Frontend Mega-Components

| File | Lines | Problem |
|---|---|---|
| CharacterCreateWizard.tsx | 3,605 | 57 `useState` calls, 7 wizard steps inline, no draft saving |
| CharacterSheetPage.tsx | 2,083 | 6 tabs + level-up flow + rest mechanics in one component |
| AsiModal.tsx | 909 | Manages ASI, feat selection, spell picking, and ability choices |

### High: Frontend Logic Duplication

`abilityMod()`, `formatMod()`, `safeJsonParse()`, `ABILITIES`, `ALL_SKILLS`, `ABILITY_FROM_ABBR`, and ASI level sets are each duplicated across 2-3 files. A shared `utils/dndRules.ts` would eliminate this.

### Medium: @JsonRawValue Deserialization Trap

Reference entities use `@JsonRawValue` (30+ occurrences), meaning their JSONB fields arrive as pre-parsed JS objects. `PlayerCharacter` JSONB fields arrive as strings. Any new code calling `JSON.parse()` on a `@JsonRawValue` field will silently break — this already caused two production bugs (see [[decisions-log]] D075).

### Medium: No Input Validation on Updates

`CharacterUpdateRequest` has no `@Valid` annotation. The update endpoint is essentially a raw database write — a client can set `level` to 100, `hpMax` to -50, or inject malformed JSON into any JSONB field.

### Low: Naming Inconsistencies

`armourClass` (British) alongside `armorProficiencies` (American) on the same entity. `multiclassClassEntries` on the create request vs `multiclassEntries` on the response.

---

## 2. Scalability

*Architect perspective*

### Current Capacity Estimate: ~50-100 Concurrent Users

Default HikariCP pool of 10 connections, no caching, in-memory STOMP broker, no indexes on foreign keys.

### Critical Bottlenecks (Failures Before 500 Users)

**In-Memory STOMP Broker** (`WebSocketConfig.java:20`): `config.enableSimpleBroker("/topic", "/queue")` stores all subscription state in JVM memory. Horizontal scaling is blocked — clients on instance A never see messages from instance B. At 5000 WebSocket connections, memory pressure will be severe.

**No Connection Pool Configuration**: Spring defaults to 10 connections. `CharacterService.levelUp()` holds a transaction open through 5-8 repository lookups. During an encounter with 5 players leveling up simultaneously, 5 of 10 connections are consumed. At 100 concurrent encounters, pool exhaustion is guaranteed.

**N+1 Queries Everywhere**:

- Loading one character = 1 base query + up to 6 lazy-loaded associations = 7 queries
- Every combat action reloads the full encounter: 1 + 8 participant lazy loads = 9 queries
- A combat round with 8 participants: ~40 encounter loads x 9 queries = ~360 queries per round

### High Bottlenecks (Degradation at 200-1000 Users)

**Zero Caching**: Every reference data request (classes, races, backgrounds, feats, spells) does a full table scan. These change only during seeding. At 5000 users, reference queries alone could consume 80%+ of DB capacity.

**Missing Database Indexes**: Hibernate `ddl-auto: update` does NOT auto-create foreign key indexes. `player_characters.user_id`, `player_characters.campaign_id`, `encounter_participants.encounter_id`, `combat_logs.encounter_id` — all missing.

**Wide Transaction Scope**: `createCharacter()` (290 lines) holds a DB connection through multiclass validation, spell slot calculation, hit dice initialization, feature collection, and JSON serialization.

### Scaling Roadmap

| Users | Key Changes |
|---|---|
| **100** | Add DB indexes on all FKs, configure HikariCP (20 pool), add JPA fetch joins for worst N+1 paths, `@Cacheable` on reference data |
| **1,000** | Replace SimpleBroker with RabbitMQ/Redis, add Flyway for migrations, PgBouncer connection pooling, pagination on combat logs, rate limiting |
| **5,000** | Horizontal app scaling (2-4 instances behind LB), PostgreSQL read replica, Redis distributed cache, incremental WebSocket deltas, observability (Actuator + Prometheus) |

---

## 3. D&D 5e Rules Accuracy

*Subject Matter Expert perspective*

### PHB Coverage Scorecard

| Category | Coverage | Rating |
|---|---|---|
| Ability Scores & Creation | Point buy, standard array, racial bonuses, Tasha's reassignment | **95%** |
| Leveling & HP | HP calc, ASI levels, proficiency bonus, feature grants | **95%** |
| Multiclass Rules | Prerequisites, spell slot calculation, HP rules | **90%** |
| Spell Slot Tables | Full/half/third/artificer/pact — all verified correct | **100%** |
| Spell Management | Known/prepared, cantrips, spellbook | **85%** |
| Feat System | Prerequisites, automated effects, reversal, resources | **90%** |
| Combat Core | Attack rolls, crits, advantage, temp HP | **85%** |
| Death Saves | Nat 1/20, stabilization, unconscious attacks | **90%** |
| Rest Mechanics | Long rest correct; short rest significantly broken | **55%** |
| Conditions | Basic add/remove with duration (no mechanical effects) | **40%** |
| Class Feature Resources | Ki, Action Surge, Channel Divinity, etc. — not tracked | **10%** |
| Movement & Positioning | Not implemented | **0%** |

**Overall: ~92% correct for character management, ~55% for combat, ~70% total.**

### Top Rules Bugs

1. **Short rest: only allows spending 1 hit die** — PHB p.186 says "one or more, up to the character's maximum."
2. **Short rest: does not reset Warlock pact slots** — PHB p.107: Warlocks regain all pact slots on short rest.
3. **Concentration saves ignore CON proficiency** — Characters proficient in CON saves (or with War Caster) should add proficiency bonus.
4. **Single spellcasting ability for multiclass** — PHB p.164 requires separate save DCs per class (e.g. Cleric/Wizard needs both WIS and INT DCs).
5. **Ability score always hard-capped at 20** — Barbarian 20 "Primal Champion" raises STR/CON max to 24.

### Missing But Expected

- Exhaustion levels (6 tiers, not a simple condition flag)
- Cantrip damage scaling with character level
- Ritual casting without slot expenditure
- Damage resistance/vulnerability/immunity automation in combat
- Arcane Recovery and similar short rest spell slot features

---

## 4. User Experience & Features

*Product Manager perspective*

### Strengths

- Character creation wizard is genuinely best-in-class — 226 races, multiclass prereqs, feat automation, wizard spellbooks
- Encounter join via session code — clean one-step onboarding
- Death save automation handles nat 1/20, massive damage, unconscious attacks
- Concentration tracking — auto-check on damage, auto-drop on 0 HP
- Level up flow — sequential modals with automatic stat recalculation

### Must-Have Gaps

| Gap | Impact |
|---|---|
| No draft saving in character wizard | 20 min of work lost to accidental navigation |
| Monster actions require manual typing every attack | DM enters "+4, 1d6+2, slashing" for each goblin each round |
| No inline HP editing on character sheet | Can't take fall damage outside combat |
| No DM view of player character sheets | Can't prep encounters without asking players |
| `window.prompt()` for temp HP | Breaks dark theme, no validation |
| No password reset flow | Users locked out permanently |
| DM encounter page is not mobile-responsive | Zero breakpoints — unusable on tablets |
| No global React error boundary | Unhandled JS error = white screen crash |
| No ARIA attributes anywhere | Completely inaccessible to screen readers |

### Quick Wins

| Win | Effort |
|---|---|
| Add `beforeunload` guard + localStorage draft to wizard | ~2 hours |
| Campaign summary on DM dashboard (active encounters, player count, resume) | ~2 hours |
| Character HP/slot preview on PlayerDashboard cards | ~30 min |
| Replace `window.prompt()` with styled modal | ~1 hour |
| Add responsive breakpoints to EncounterSessionPage | 1-2 hours |

---

## 5. Session Automation

*End User perspective*

### Pain Point Status

| Common D&D Pain Point | Status |
|---|---|
| Tracking HP, temp HP, death saves | **Solved** — full automation with correct rules |
| Tracking spell slots | **Solved** — clickable pips, long rest reset, works in encounters |
| Tracking class abilities (Ki, Action Surge, etc.) | **Not Addressed** — no class resource system |
| Forgetting condition effects | **Partially Solved** — tracked visually, but don't affect rolls |
| Slow combat turns | **Not Addressed** — no saved attack profiles |
| Lost character sheets | **Solved** — persistent cloud storage |
| Calculating attack/damage bonuses | **Partially Solved** — calculated on sheet, not auto-filled in encounter |
| DM managing 5+ monster stat blocks | **Partially Solved** — bestiary exists but not accessible from encounter session |
| Managing initiative | **Solved** — clear turn indicator, auto-advance |
| Players forgetting what features/spells do | **Partially Solved** — detail cards exist but not accessible from encounter |

### Top 10 Automation Features by Time Saved Per Session

1. **Saved attack/action profiles** — one-click "Longsword +7, 1d8+4 slashing" instead of retyping 3 fields every attack. Pre-populate from monster stat blocks. *(saves 2-5 min per combat per player)*
2. **Class resource tracking** — generic resource pools (Ki, Action Surge, Rage, etc.) with short/long rest resets. The feat resource system already exists — extend it. *(prevents 3-10 forgotten uses per session)*
3. **Inline monster stat block in encounter** — collapsible stat block per monster row. MonsterStatBlock component already exists. *(saves 1-3 min per DM turn)*
4. **Conditions mechanically affect rolls** — auto-apply disadvantage for restrained, auto-crit paralyzed in melee, etc. *(prevents 2-5 rule errors per combat)*
5. **Cast Spell workflow in encounter** — select spell, auto-deduct slot, auto-set concentration, show description. *(saves 1-2 min per caster turn)*
6. **"What can I do" turn helper** — action/bonus action/movement/reaction checklist on your turn. *(saves 1-2 min for new players per turn)*
7. **Legendary/lair action automation** — track uses per round, prompt between turns, lair actions at initiative 20. *(saves 1-3 min per round for boss fights)*
8. **General purpose dice roller** — d4/d6/d8/d10/d12/d20 with modifiers, accessible from any screen. *(saves 30 sec per ad-hoc roll)*
9. **Encounter-to-character-sheet HP sync** — write back HP, temp HP, and spent slots when encounter ends. *(prevents post-session data loss)*
10. **Short rest fix + class resource resets** — correct hit dice spending, warlock pact slot recovery, class feature resets. *(bug fix + feature completion)*

---

## 6. Onboarding & Documentation

*New Engineer perspective*

### What Works Well

- `obsidian-docs/` is genuinely exceptional — architecture diagrams, full database schema, every API endpoint, 75 decisions-log entries with rationale, known issues with reproduction steps
- README gets you running in under 5 minutes
- Backend package structure is clean and consistent (Controller/Service/Repository/Entity per domain)

### What Trips Up a Newcomer

1. **Every JSONB field is `String`** — you cannot know the shape of `spellsKnown`, `levelHistory`, or `features` from the types. Must check [[database-schema]] or read seeder code.
2. **Reference entities vs PlayerCharacter have different JSON behavior** — `@JsonRawValue` means reference JSONB arrives as pre-parsed objects; character JSONB arrives as strings needing `JSON.parse()`.
3. **D&D rule constants scattered across 6+ files** — third-caster subclass lists, ASI levels, spell slot tables, spellcaster class lists each appear in multiple backend and frontend files with no single source of truth.
4. **Documentation exists in 4+ locations** — README, obsidian-docs/, docs/, PROJECT_DOCUMENTATION.md, CLAUDE_CODE_BRIEF.md. Unclear which is canonical.

### First-Week Survival Guide

1. Start with `obsidian-docs/architecture.md` then `database-schema.md` then `api-reference.md`
2. Every JSONB field is a `String` that needs parsing — always use `safeJsonParse` on frontend
3. Reference entities use `@JsonRawValue` (pre-parsed); PlayerCharacter fields are strings (need `JSON.parse`)
4. The two biggest files: CharacterCreateWizard.tsx (3605L) and CharacterService.java (1420L)
5. Combat is REST-then-broadcast: HTTP for mutations, WebSocket for state sync
6. There are no tests — everything is manually tested
7. When changing any D&D rule constant, grep the entire codebase

---

## Prioritized Action Plan

*All items completed 2026-07-20.*

### Week 1: Fix Critical Bugs & De-Risk

| Day | Action | Status |
|---|---|---|
| 1 | Replace all `catch (Exception ignored) {}` with logging; throw for critical operations | Done |
| 2 | Fix short rest: allow multiple hit dice, reset warlock pact slots | Done |
| 2 | Fix concentration saves to include CON proficiency bonus | Done |
| 3 | Add typed records for JSONB structures (LevelHistoryEntry, MulticlassEntry, etc.) | Done |
| 3 | Add `@Valid` to update endpoint, min/max constraints | Done |
| 4 | Write integration tests for levelUp/levelDown round-trip (39 tests) | Done |
| 5 | Add `beforeunload` guard + localStorage draft to wizard | Done |

### Week 2: Structural Improvements

| Day | Action | Status |
|---|---|---|
| 6 | Extract shared frontend utilities into `utils/dndRules.ts` | Done |
| 7-8 | Split CharacterCreateWizard into step components (wizard/constants.ts, wizard/StepComponents.tsx, wizard/SpellSteps.tsx) | Done — 3664→2377 lines main, 3 modules extracted |
| 9 | Add DB indexes on all FKs + GIN index on spells.classes | Done |
| 9 | Add `@Cacheable` on reference data endpoints (13 endpoints) | Done |
| 10 | CharacterService extraction (CharacterMapper, CharacterJsonHelper) | Done — 1420→997 lines

### Next Sprint

- Configure HikariCP (20 pool)
- Add JPA fetch joins for worst N+1 paths
- Replace SimpleBroker with RabbitMQ/Redis for horizontal scaling
- Add Flyway for schema migration management
- Build saved attack profiles for encounter combat
- Add class resource tracking system
- Add global React error boundary
- Make encounter session pages mobile-responsive
