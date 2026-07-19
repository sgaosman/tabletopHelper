# Feature Roadmap

## Milestone Status

| # | Milestone | Status | Notes |
|---|-----------|--------|-------|
| 1 | Project Setup & Authentication | Complete | Auth, JWT, login/register UI |
| 2 | Campaign Management & Character Sheets | Complete | Campaigns, invite codes, character CRUD |
| 3 | 5e.tools Data Import & Reference Browsing | Complete | Bestiary, spells, items, conditions, quick rules reference |
| 4 | Encounter Builder & WebSocket Setup | Complete | Encounter CRUD, participant management, WebSocket real-time sync, multiselect filters |
| 5 | Combat Engine | Complete | Full combat: damage, healing, conditions (with duration tracking), death saves, concentration, attack rolls, spell slot tracking, turn-based auto-expiry |
| 6 | Polish, Mobile & Deployment | Deferred | Will be done after M14 when combat UI has stabilised |
| 7 | Data Gathering & Spell Effect Schema | Complete | 288 spells, 104 items, 2,357 monsters, class/race analysis — data files and review docs produced |
| 8 | Spell Effect Data Population & Review Cycle | Complete | All data files validated and approved — 2 critical, 2 moderate, 48 markup fixes applied |
| 9 | Character Builder Overhaul | Complete | Reference data entities, 5etools seeders, 6-step creation wizard, 6-tab character sheet, rest mechanics, proficiency display, character deletion, campaign assignment |
| 10 | Character Leveling & Multiclass | Not started | Create at any level, multiclass at creation, level up/down with auto-recalculation of all derived stats |
| 11 | Spell Resolver Engine & Encounter Spellcasting | Not started | Cast Spell action, auto-resolution, source-tracked conditions, component checks, optimistic locking |
| 12 | Monster Actions, Legendary Actions & Resistance | Not started | Structured action data, DM action panel, legendary action pool, legendary resistance, lair actions |
| 13 | Enhanced Action Economy | Not started | Reactions, bonus actions, free object interactions, Dodge/Help/Hide/Dash, item use, bonus-action-spell rule |
| 14 | Undo System | Not started | Before-state snapshots on every combat action, DM-only rollback with cascade support |
| 15 | Persistent Spell Effects as Companion Participants | Not started | Spiritual Weapon, Flaming Sphere etc. as sub-cards beneath the caster |
| 16 | Short/Long Rest System | Complete (in M9) | Implemented as part of M9 character sheet — hit dice spending, HP recovery, spell slot reset on long rest |
| 17 | Class Feature Automation | Not started | Second Wind, Channel Divinity, Action Surge, Bardic Inspiration, Wild Shape, Ki Points, Rage, etc. |
| 18 | Sorcerer Metamagic | Not started | Twinned, Quickened, Subtle, Heightened Spell, Sorcery Point tracking |

## Milestone 3: 5e.tools Data Import & Reference Browsing

**Goal:** The full bestiary, spells, conditions, and items from all 2014-era D&D 5e sourcebooks are browsable.

**Backend tasks:**
- [x] Create Monster, Spell, Condition, Item entities and repositories
- [x] Download 2014 5e.tools data (from `5etools-mirror-2` GitHub repo, 2014 branch)
- [x] Verify data is 2014 edition only (no `XPHB`, `XMM`, `XDMG` source codes)
- [x] Create `FiveEToolsMarkupParser` utility (converts `{@atk mw}`, `{@hit 5}`, etc. to clean text)
- [x] Create `DataSeeder` CommandLineRunner with per-entity-type seeders
- [x] Handle 5e.tools JSON format quirks (`ac` as int or array, `cr` as string or object, type codes with `|SOURCE` suffix, nested `choose` type patterns, `innate` spell collections)
- [x] Create paginated, searchable, filterable endpoints for monsters, spells, items, conditions
- [x] Quick reference endpoint serving `bookref-quick.json` data

**Frontend tasks:**
- [x] BestiaryPage — searchable list with filters for name, type, CR, source; full source names; clear filters button
- [x] SpellsPage — searchable list with filters for name, level, school, class/subclass, concentration, ritual, source; full source names; clear filters button
- [x] ConditionsPage — full-width accordion with bold-highlighted key terms, exhaustion level table
- [x] ItemsPage — searchable list with filters for name, type, rarity, source; full source names; clear filters button
- [x] MonsterStatBlock, SpellCard, ItemCard components
- [x] QuickReferencePage — 5 chapters (Character Creation, Equipment, Playing the Game, Combat, Movement) with left sidebar index, accordion sections, table/list/inset rendering, 5e.tools markup parsing
- [x] Sticky navigation bars on all pages
- [x] Source abbreviation-to-full-name mapping (130+ sources)

## Milestone 4: Encounter Builder & WebSocket Setup

**Goal:** DM can create encounters, add monsters and PCs, and WebSocket connections work.

**Backend tasks:**
- [x] Encounter and EncounterParticipant entities with full schema (status, initiative, HP, AC, conditions, death saves, concentration)
- [x] EncounterRepository and EncounterParticipantRepository
- [x] EncounterService — full CRUD, participant management, initiative rolling, encounter lifecycle (PREPARING → ACTIVE → PAUSED → COMPLETED)
- [x] EncounterController — REST endpoints at `/api/encounters/**` with WebSocket broadcast after every mutation
- [x] WebSocketConfig with STOMP over SockJS at `/ws`, message broker on `/topic` and `/queue`
- [x] WebSocketAuthInterceptor — JWT validation on STOMP CONNECT frames via Authorization header
- [x] EncounterWebSocketController — join handler with state broadcast
- [x] Session code generation (same pattern as campaign invite codes)
- [x] Monster auto-populate (HP, AC, dex mod for initiative) with quantity naming ("Goblin 1", "Goblin 2")
- [x] Player character auto-populate from character sheet (HP, AC, initiative bonus)
- [x] Fuzzy monster search endpoint (`GET /monsters/search`) using `pg_trgm` with `word_similarity()`, threshold 0.4, gin index on `LOWER(name)`
- [x] Participant rename endpoint (`PATCH /encounters/{id}/participants/{participantId}/name`) — updates displayName while preserving monsterId FK

**Frontend tasks:**
- [x] TypeScript interfaces for Encounter, EncounterParticipant, all request/response types
- [x] encounterApi — REST client for all encounter endpoints
- [x] useWebSocket hook — STOMP connection lifecycle, JWT auth, subscriptions, auto-reconnect (5s delay)
- [x] EncounterContext — live encounter state from WebSocket + REST fallback
- [x] EncounterBuilderPage — full-width layout, campaign selector, encounter list with status badges, create form, add PCs from campaign, real-time fuzzy monster search (debounced 300ms) with quantity, inline manual initiative input per participant (blur/Enter to save), inline participant rename (preserves monsterId FK), roll-all initiative, start encounter
- [x] EncounterSessionPage (DM) — initiative order, HP/AC/conditions display, pause/resume/end controls, session code copy, WebSocket connection status
- [x] EncounterSessionPage (Player) — read-only view with own character highlighted, "it's your turn" notification, visibility filtering
- [x] JoinEncounterPage — session code entry for players
- [x] PlayerDashboard — "Join Encounter" section added
- [x] Routes wired: `/dm/encounters`, `/dm/encounter/:id/session`, `/player/encounter/join`, `/player/encounter/:id/session`

**Multiselect filters (shipped alongside M4):**
- [x] MultiSelect reusable component with checkboxes, search, dark theme
- [x] Backend: comma-separated params split in Java, passed as Collection to Spring Data JPA `IN (:list)` clauses
- [x] ItemsPage — type, rarity, source converted to multiselect
- [x] BestiaryPage — type, CR, source converted to multiselect
- [x] SpellsPage — all filters multiselect: level, school, class, subclass, source. Subclass aggregates across all selected classes. Class + subclass combined into single OR query against jsonb classes array

## Milestone 5: Combat Engine

**Goal:** Full real-time combat with all core D&D 5e mechanics.

**Backend tasks:**
- [x] CombatService — applyDamage, applyHealing, setHp, addCondition, removeCondition, rollDeathSave, setConcentration, advanceTurn, previousTurn, checkConcentration (auto on damage), getCombatLog
- [x] CombatController — REST endpoints at `/api/encounters/{id}/combat/*` with WebSocket broadcast after every mutation
- [x] CombatLog entity + CombatLogRepository — append-only action log with round, actor, target, action type, description, roll values, damage/healing amounts
- [x] CombatActionType enum — ATTACK, DAMAGE, HEAL, CONDITION_ADD, CONDITION_REMOVE, DEATH_SAVE, CONCENTRATION_CHECK, CONCENTRATION_LOST, TURN_ADVANCE, TURN_BACK, STABILIZE, KILL, REVIVE
- [x] Damage flow — temp HP absorbs first, drop to 0 kills monsters/puts players in dying state, auto concentration check
- [x] Healing flow — capped at max HP, revives dying players, resets death saves
- [x] Death saving throws — d20 server roll, nat 20 revives with 1 HP, nat 1 = 2 failures, 10+ success, <10 failure, 3 of either = stabilize/death
- [x] Concentration checks — auto-triggered on damage, CON save vs DC max(10, damage/2), uses creature's CON modifier
- [x] Temporary HP — damage reduces temp HP first before current HP
- [x] Turn management — advance/back with round counter auto-increment/decrement
- [x] Permission enforcement — DM can do everything, players can only act on their own controlled participants
- [x] Direct HP override (setHp) — DM can set exact HP/temp HP values

**Frontend tasks:**
- [x] combatApi.ts — REST client for all 10 combat endpoints
- [x] CombatLogEntry type
- [x] DM EncounterSessionPage — full rewrite with combat controls:
  - [x] ActionPanel — damage (with damage type), heal, add condition (dropdown), set concentration forms
  - [x] HpBar component — visual HP bar with temp HP overlay, color transitions (green > yellow > red)
  - [x] ConditionBadges — clickable to remove, color-coded per condition type
  - [x] DeathSaves — visual circles (3 success / 3 failure) with roll button for dying players
  - [x] CombatLog — collapsible log panel with color-coded entries, auto-scroll, 3s polling
  - [x] Turn controls — Next Turn / Previous Turn buttons
  - [x] Quick action buttons on each participant row (damage, heal, condition, concentration)
  - [x] Confirmation dialog on ending encounter
- [x] Player EncounterSessionPage — updated with:
  - [x] HpBar for own character
  - [x] Death save roll button when own character is dying
  - [x] Combat log panel (read-only)
  - [x] Condition display with color coding

**Completed (previously deferred):**
- [x] Attack roll flow (d20 vs AC → auto damage on hit, advantage/disadvantage, critical hits, dice parser)
- [x] Spell slot tracking and deduction (copied from character on encounter join, use/restore endpoints, bubble UI)
- [x] Condition duration tracking (auto-removal after N rounds at start of creature's turn)
- [x] Start-of-turn effects (condition expiry check integrated into advanceTurn)
- [x] Multi-attack UI (up to 5 attack rows per action, "+" to add, copies previous row values, "Roll N Attacks" button)
- [x] Force crit toggle on attack rolls (for melee attacks against unconscious targets within 5ft)
- [x] Unconscious combat rules: auto-hit downed PCs, damage causes death save failures (1 normal, 2 on crit), massive damage instant kill
- [x] Concentration auto-drops on 0 HP (fixed ordering bug where isAlive was set before the check)
- [x] Resurrection: dead PCs (3 failed saves) can be healed back, auto-applies Prone, clears Unconscious
- [x] Combat log improvements: turn participant tracking per entry, round/turn section headers, smart scroll with "new messages" indicator
- [x] Damage and condition buttons available for downed PCs
- [x] Player combat permissions: self-manage conditions/concentration, attack on own turn
- [x] Player encounter session page: attack panel, condition/concentration controls, improved combat log
- [x] Per-row clone button for multi-attack (copies all filled values)

## Milestone 6: Polish, Mobile & Deployment (DEFERRED)

**Status:** Deferred — will be done after M14 when combat UI has stabilised. The encounter session UI will be substantially rebuilt by M11–M13, making early polish work throwaway.

**Tasks:**
- [ ] Mobile-responsive encounter screens
- [ ] Error handling — toast notifications, WebSocket disconnect/reconnect
- [ ] Loading states for all async operations
- [ ] Dockerfiles (backend + frontend)
- [ ] docker-compose.prod.yml
- [ ] Nginx configuration (reverse proxy + SSL)
- [ ] Deploy to Hetzner VPS or Railway
- [ ] End-to-end testing with real devices

## Milestone 7: Data Gathering & Spell Effect Schema

**Goal:** Produce structured data definitions for the spell resolver, combat automation, character builder, and monster action systems. No implementation code — output is JSON data files and analysis documents for human review.

**Sub-tasks:**
- [ ] **Task 1: Spell Effect Definitions (Levels 0–3)** — Structured JSON definition for every spell at levels 0–3 (~294 spells). Each spell classified into a pattern category (ATTACK_DAMAGE, SAVE_DAMAGE, SAVE_CONDITION, HEAL, BUFF_NO_ROLL, etc.) with delivery method, targeting, effects array, upcast scaling, cantrip scaling, and `requiresManualResolution` flag for complex spells.
  - Output: `backend/src/main/resources/data/spell-effects/spell-effect-definitions.json`
  - Review: `docs/spell-effect-review.md`
- [ ] **Task 2: Class Feature Analysis (Levels 1–5)** — Every class and subclass feature at levels 1–5, categorised as COMBAT_ACTIVE, COMBAT_PASSIVE, COMBAT_MODIFIER, RESOURCE, FLAVOUR, or SPELLCASTING. Includes uses, recharge period, and combat notes.
  - Output: `docs/class-feature-analysis.md`
- [ ] **Task 3: Race Trait Analysis** — Every racial trait categorised as STAT_BONUS, COMBAT_ACTIVE, COMBAT_PASSIVE, PROFICIENCY, MOVEMENT, SENSE, RESISTANCE, or FLAVOUR.
  - Output: `docs/race-trait-analysis.md`
- [ ] **Task 4: Item Effect Analysis** — Combat-relevant items (potions, wands, staves, scrolls) with structured effect definitions. ~50–80 items.
  - Output: `backend/src/main/resources/data/item-effects/item-effect-definitions.json`
  - Review: `docs/item-effect-review.md`
- [ ] **Task 5: Monster Action Structured Data** — Structured action templates for ~1,300–1,600 monsters: all CR 0–10 (~1,200–1,500), all legendary/lair monsters at any CR (~60–80 additional), and CR 11–15 as secondary priority if time permits (~200–300 additional). Parses attack bonuses, damage dice, save DCs, conditions, recharge, legendary action costs, spellcasting blocks from raw 5e.tools JSON.
  - Output: `backend/src/main/resources/data/monster-actions/monster-action-definitions.json`
  - Review: `docs/monster-action-review.md`
- [ ] **Task 6: Validation Summary** — Cross-check all definitions against 5e.tools metadata, report data quality issues and recommended review priorities.
  - Output: `docs/data-gathering-summary.md`

**Critical rules:** No implementation code. No modifications to existing source code, entities, database schema, or database data. Flag uncertainty with "REVIEW:" notes. Be conservative about automation — mark complex spells as manual resolution.

## Milestone 8: Spell Effect Data Population & Review Cycle

**Goal:** Human review and finalisation of all 294 spell effect definitions from M7.

**Tasks:**
- [x] Human reviews `docs/spell-effect-review.md` and flags corrections
- [x] Corrections applied to `spell-effect-definitions.json` (Snare/Earthbind saveToEndEachTurn fixed, 48 markup tags stripped)
- [x] Re-run validation checks (25/25 passed — cross-reference 5e.tools metadata, cantrip scaling, delivery methods, type checks, structural integrity)
- [x] Final sign-off on all spell definitions — see `docs/m8-final-signoff.md`
- [x] Human reviews `docs/class-feature-analysis.md`, `docs/race-trait-analysis.md`, `docs/item-effect-review.md`, `docs/monster-action-review.md`
- [x] Corrections applied to all data files (Mummy Lord damage + LR fixed, markup cleaned)
- [x] All data files approved for use in subsequent milestones

## Milestone 9: Character Builder Overhaul

**Goal:** Replace free-text character creation with guided selection from seeded reference data. New character sheet with six tabs: Stats, Actions, Spells, Inventory, Features, Journal.

**Note:** The Spells tab was initially blocked by M7/M8 (data gathering). Now complete: full spell management UI with source-grouped display, preparation/known spell management modals, spell detail view, race/feat spell boxes, and spell selection during character creation. Spell slot calculation is automated via SpellSlotCalculator. Feat spell management added: background feat selection during creation (option picker, ability picker, spell selection), "Add Feat Spells" modal on character sheet for post-creation feat acquisition. Feat `grantsFeatures` (5etools format) parsed by `featSpellParser.ts` into normalized `ParsedFeatOption` structure.

**Backend tasks:**
- [x] Seed `Race` entity from 5e.tools `races.json` (226 races with size, speed, ASI, proficiencies, features, creature type)
- [x] Seed `CharacterClass` entity from 5e.tools `class-*.json` files (hit dice, proficiencies, features per level, spell list type: prepared/known/spellbook)
- [x] Seed `Subclass` entity with subclass features, domain/oath/circle spells
- [x] Seed `Background` entity with proficiencies, equipment, features
- [x] Seed `Feat` entity with prerequisites and effects
- [x] Delete existing test characters (all current `player_characters` are test data — see [[decisions-log#D035]])
- [x] Character creation API: race → class → ability scores → background → derived stats auto-calculated
- [x] Ability score methods: manual entry, standard array, point buy, 4d6 drop lowest (server-side roll)
- [x] Tasha's ability score reassignment (move racial ASI to different abilities)
- [x] Multiclass support: add 2nd–5th class following PHB multiclassing rules
- [x] Auto-calculate derived stats: proficiency bonus, ability modifiers, saving throw bonuses, skill bonuses, spell save DC, spell attack bonus, initiative bonus, HP
- [x] HP calculation: first level (max hit die + CON mod), higher levels (average, set, or roll per class rules)
- [x] Equipment and currency management endpoints
- [x] Attunement tracking (max 3 items)
- [x] Character soft-delete endpoint (`DELETE /characters/{characterId}`) — sets `isActive = false`, blocked if character is in active combat (PREPARING/ACTIVE/PAUSED encounter)
- [x] `IllegalStateException` → 409 Conflict global exception handler
- [x] Proficiency JSONB columns: `armor_proficiencies`, `weapon_proficiencies`, `tool_proficiencies`, `language_proficiencies`
- [x] `clearCampaign` boolean on update request for campaign unassignment
- [x] `EncounterParticipantRepository.existsByCharacter_IdAndEncounter_StatusIn()` for active-combat check
- [x] BackgroundSeeder recursive `_copy` resolution (depth limit 5) and `{"any": N}` proficiency parsing
- [x] Race `additional_spells` JSONB column — RaceSeeder parses all 8 5etools `additionalSpells` patterns (known, innate, expanded, ability string/choose, multi-option) into normalized format (76/226 races populated)
- [x] CharacterService auto-calculates spell slots (via SpellSlotCalculator), spellSaveDc, spellAttackBonus at character creation for spellcaster classes
- [x] Character creation wizard spell selection step: cantrip picker, known spell picker (known casters), prepared caster info notice
- [x] Background feat detection and configuration during character creation: feat selection (multi-feat backgrounds), option picker, ability picker, feat spell selection in Spells step
- [x] Feat spell parser (`featSpellParser.ts`): parses 5etools `additionalSpells` format into normalized `ParsedFeatOption` (handles known/innate/daily/choose-filter/choose-from-list/ability patterns)
- [x] Frontend feat spell management: "Add Feat Spells" modal on character sheet for post-creation feat acquisition, spell-granting feat search, option/ability/spell configuration
- [x] Feat spell removal: remove button on feat spell boxes with inline confirmation
- [x] Non-caster class spell section hiding: classes like Fighter don't show an empty class spell section when they only have feat spells; spellcaster subclasses (Eldritch Knight, Arcane Trickster) are excluded from this check
- [x] Spell preparation limit enforcement in Change Prepared modal
- [x] Max spell level enforcement in Manage Known modal
- [x] Cantrip exclusion from preparation/known modals
- [x] Wizard cantrip swapping from level 3+ (Cantrip Formulas class feature)

**Frontend tasks:**
- [x] Character creation wizard: race selector (with ASI preview and Tasha's reassignment) → class selector (with hit die and proficiency preview) → subclass selector (if level ≥ 3) → ability scores (method selector + inputs) → background → alignment → campaign assignment
- [x] Background proficiency pickers: "Any Gaming Set"/"Any Artisan's Tool"/"Any Musical Instrument" tool categories expanded to concrete options; `{"any": N}` structured entries rendered as pickers
- [x] Exotic language support: full 18-language list (8 standard + 8 exotic + Druidic + Thieves' Cant) in race and background pickers
- [x] Background equipment rendering: all 10+ item patterns (string, displayName, containsValue, equipmentType, quantity, worthValue, value, special), `fmtCurrency()` for cp/sp/gp, `strip5eMarkup()` for `{@item ...}` tags, equipment choice groups with "-or-" separator
- [x] Proficiency collection at creation: merge from race + class + background, deduplicate, save to 4 JSONB columns
- [x] New character sheet tabs:
  - [x] **Stats** — HP (current/max), ability scores + modifiers, speed, AC, darkvision, proficiency bonus, initiative bonus, hit dice (remaining/total), spell slots (used/remaining), saving throw bonuses + proficiencies, skill bonuses + proficiencies (colored bullets for proficiency, stars ★ for expertise), weapon/armor/tool/language proficiencies section
  - [x] **Actions** — attack actions with equipped weapons (extra attack reminder), class actions (Channel Divinity, Second Wind), feat actions, race actions
  - [x] **Spells** — spell slots display (regular + pact), spells grouped by source (per-class boxes with prepared/known badges, race innate spell box, feat spell box), spell detail modal (SpellCard), "Change Prepared" / "Manage Known" modals with class-filtered search, prepare/unprepare toggles with limit enforcement, always-prepared subclass spells (lock icon), "Add Feat Spells" modal (search spell-granting feats, configure options/ability/spells, save to spellsKnown)
  - [x] **Inventory** — currency (gp, sp, cp, pp), all items (from class/background + added), equipped items, attuned items (indicator), "+" button to add items from reference database
  - [x] **Features** — class features, race features, background features, other features (text descriptions for reference)
  - [x] **Journal** — character image (with upload), alignment, physical description, personality traits, ideals, bonds, flaws, notes
- [x] Campaign assignment dropdown on character sheet (to the right of tab navigation)
- [x] Character deletion: persistent trash icon on player dashboard cards, confirmation modal requiring exact name input, backend error display (e.g. active combat), soft-delete via API

## Milestone 10: Character Leveling & Multiclass

**Goal:** Support creating characters at any level (1–20) with optional multiclassing, and level up/down from the character sheet with automatic recalculation of all derived stats.

**Analysis tasks:**
- [ ] Extend `docs/class-feature-analysis.md` from levels 1–5 to levels 1–20 for all 13 classes and all subclasses
- [ ] Categorize every level-up gain as one of: STAT_CHANGE (automatable — HP, proficiency bonus, cantrips known, spells known, spell slots), RESOURCE_GRANT (automatable — ASI, feat, Extra Attack, class resource pools), FEATURE_DISPLAY (add to Features tab but no mechanical automation), SUBCLASS_FEATURE (display + always-prepared spells if applicable)
- [ ] Map multiclass prerequisites per class (PHB Table: ability score minimums to enter/leave a class)
- [ ] Map multiclass proficiency grants per class (differ from first-class proficiencies — PHB Multiclassing Proficiencies table)
- [ ] Identify which level-up gains are reversible on level-down and which require user confirmation (e.g., ASI choices, feat selections, spell choices)

**Feature 1: Multi-level character creation**

Create characters at any level from 1 to 20. The creation wizard adapts to the chosen level:

Backend tasks:
- [ ] Accept `level` field on `CharacterCreateRequest` (currently defaults to 1)
- [ ] Auto-calculate HP for levels 2+: first level = max hit die + CON mod, each subsequent level = average hit die (rounded up) + CON mod (default), or allow manual total
- [ ] Auto-calculate proficiency bonus from level (2 at L1–4, 3 at L5–8, 4 at L9–12, 5 at L13–16, 6 at L17–20)
- [ ] Recalculate all derived stats (spell save DC, spell attack bonus, saving throw bonuses, skill bonuses) from proficiency bonus and ability scores
- [ ] Auto-generate spell slots from `SpellSlotCalculator` for the given level

Frontend tasks:
- [ ] Level picker (1–20) in creation wizard Basic Info step
- [ ] HP calculation display showing per-level breakdown
- [ ] Cantrip count adjusts to level (from `CANTRIPS_KNOWN` tables)
- [ ] Spells known count adjusts to level (from `SPELLS_KNOWN` tables)
- [ ] Spell selection allows spells up to the max spell level for the class at that level
- [ ] ASI/feat selection UI for each ASI level reached (class-dependent: most at 4, 8, 12, 16, 19; Fighter adds 6, 14; Rogue adds 10)
- [ ] Class features for all levels up to chosen level added to `features` automatically
- [ ] Subclass features added if level >= subclass level

**Feature 2: Multiclass character creation**

When creating a character at level 2+, allow splitting levels across multiple classes:

Backend tasks:
- [ ] Add `multiclass_requirements` JSONB column to `character_classes` table — PHB multiclass prerequisites (e.g., Fighter requires STR 13 or DEX 13, Paladin requires STR 13 and CHA 13)
- [ ] Seed multiclass prerequisites from PHB data
- [ ] Validate multiclass eligibility: character must meet prerequisites for BOTH the current class (to leave it) AND the new class (to enter it)
- [ ] Multiclass proficiency grants: when adding a second+ class, grant only the reduced proficiency set (PHB Multiclassing Proficiencies table), not the full first-class set
- [ ] `multiclassEntries` JSONB on `player_characters` stores `[{classId, className, subclassId?, subclassName?, level}]`
- [ ] Spell slot calculation uses combined caster levels via `SpellSlotCalculator` (already handles multiclass + pact magic)

Frontend tasks:
- [ ] "Add Class" button in creation wizard when level >= 2, with prerequisite validation against current ability scores
- [ ] Level allocation UI: distribute total character level across chosen classes
- [ ] Per-class subclass selection when class level >= subclass level
- [ ] Per-class feature collection for each class at its allocated level
- [ ] Multiclass proficiency display (reduced set for secondary classes)
- [ ] Combined spell slot display for multiclass casters

**Feature 3: Level up/down from character sheet**

The largest feature. A "Level Up" and "Level Down" button on the character sheet. All derived stats recalculate automatically.

Backend tasks:
- [ ] `POST /api/characters/{id}/level-up` endpoint — accepts `classId` (which class to add a level in), returns updated character
- [ ] `POST /api/characters/{id}/level-down` endpoint — accepts `classId` (which class to remove a level from), returns updated character
- [ ] Level-up auto-updates:
  - `level` incremented
  - `hpMax` increased by hit die average (rounded up) + CON mod for the class being leveled (configurable: average or rolled)
  - `proficiencyBonus` recalculated from total level
  - `spellSlots` recalculated via `SpellSlotCalculator`
  - `spellSaveDc` and `spellAttackBonus` recalculated from proficiency bonus
  - `features` array gets new features for the gained level (from class and subclass seeded data)
  - `hitDiceMap` updated (total hit dice for the leveled class incremented)
  - Cantrips known count updated (if class gains a cantrip at this level)
  - Spells known count updated (if known caster and this level grants more)
  - `multiclassEntries` updated (if multiclassed)
- [ ] Level-down auto-updates: reverse of above — decrement HP, remove features for the lost level, reduce hit dice, recalculate spell slots. If last level in a class is removed, remove that class entry from `multiclassEntries`
- [ ] ASI/feat handling at level up: if the new level is an ASI level for the leveled class, return a flag indicating ASI/feat choice is needed. Frontend sends a follow-up request with the choice.
- [ ] `POST /api/characters/{id}/apply-asi` endpoint — accepts ability score increases (+2 to one or +1 to two) or a feat selection. Recalculates all ability-dependent derived stats (save DC, attack bonus, HP if CON changed, prepared spell count if casting ability changed)
- [ ] Level-down ASI reversal: if removing a level that had an ASI, prompt user to confirm which ASI/feat to remove. Store ASI/feat history per level in a `levelHistory` JSONB column so rollback is deterministic.
- [ ] Validation: cannot level down below 1. Cannot level up above 20. Multiclass level-down cannot remove the last class (character must always have at least 1 class).

Frontend tasks:
- [ ] "Level Up" button on character sheet header (next to level display)
- [ ] Level-up flow:
  - If single-classed: confirm class or choose to multiclass (with prereq validation)
  - If multiclassed: choose which class to level
  - Show what the character gains at the new level (features, HP, spell slots, cantrips, etc.)
  - If ASI level: inline ASI/feat chooser (+2/+1/+1 ability selector, or feat picker with prereq validation)
  - If known caster gaining spells: spell selection for new slots
  - Confirmation and save
- [ ] "Level Down" button (with confirmation modal)
  - If single-classed: straightforward — remove last level
  - If multiclassed: choose which class to reduce
  - Show what will be lost (features, HP, spell slots, etc.)
  - If removing an ASI level: show which ASI/feat will be reversed (from `levelHistory`)
  - If known caster losing spell slots: prompt which spells to remove if over the new known limit
  - Confirmation and save
- [ ] Level history display (optional): collapsible timeline showing what was gained at each level

**Derived stat recalculation reference:**

| Stat | Recalculation |
|------|--------------|
| HP | Sum of: (max hit die + CON mod) for level 1 + (avg hit die + CON mod) per subsequent level per class |
| Proficiency bonus | (total level - 1) / 4 + 2, floored |
| Spell slots | `SpellSlotCalculator` with combined caster levels (full = level, half = level/2 floor, artificer = level/2 ceil, pact = separate) |
| Spell save DC | 8 + proficiency bonus + casting ability mod |
| Spell attack bonus | proficiency bonus + casting ability mod |
| Cantrips known | `CANTRIPS_KNOWN[className][classLevel]` |
| Spells known | `SPELLS_KNOWN[className][classLevel]` (known casters only) |
| Prepared spell count | ability mod + class level (full casters), ability mod + floor(level/2) (half casters), minimum 1 |
| Saving throw bonuses | ability mod + (proficiency bonus if proficient) |
| Skill bonuses | ability mod + (proficiency bonus if proficient) + (proficiency bonus again if expertise) |
| Hit dice | per-class: {total: class level, remaining: class level, faces: class hit die} |

## Milestone 11: Spell Resolver Engine & Encounter Spellcasting

**Goal:** "Cast Spell" combat action with fully automated resolution for ~85% of level 0–3 spells.

**Backend tasks:**
- [ ] Enrich `Spell` entity with missing columns from 5e.tools data: `conditionInflict`, `spellAttack`, `scalingLevelDice`, `areaTags`, `miscTags`, `affectsCreatureType`
- [ ] Add `effectTemplate` JSONB column to `Spell` entity, populated from M7/M8 spell definitions
- [ ] Update seeder to extract currently-ignored 5e.tools fields
- [ ] Re-seed spells with enriched data (migration strategy for existing data)
- [ ] `SpellResolverEngine` — interprets effect templates server-side:
  - Validate: caster has slot, spell is prepared, components satisfied (costly material check against inventory)
  - Deduct spell slot (cantrips: no deduction)
  - Calculate upcast scaling (damage dice, target count)
  - For SPELL_ATTACK: roll d20 + spell attack bonus vs each target's AC
  - For SAVING_THROW: each target rolls save vs caster's spell save DC (half-on-save handling)
  - For AUTO_HIT: effects apply automatically
  - Apply damage through existing damage pipeline (respects temp HP, death saves, etc.)
  - Apply conditions with source tracking (sourceSpellName, sourceParticipantId, sourceRequiresConcentration) — see [[decisions-log#D032]]
  - Set concentration (auto-drop previous concentration)
  - Log everything to combat log with detailed entries
  - Broadcast via WebSocket
- [ ] `CastSpellRequest` DTO: spellId, slotLevel, targetParticipantIds, actorParticipantId
- [ ] `POST /api/encounters/{id}/combat/cast-spell` endpoint
- [ ] Verbal component check: if caster has Silence effect, block spells with verbal components
- [ ] Cantrip scaling: use character level, not class level
- [ ] Concentration drop cascade: when concentration drops or caster dies, auto-remove all conditions from all targets with matching sourceSpellName + sourceParticipantId
- [ ] `@Version` optimistic locking on `Encounter` and `EncounterParticipant` entities — see [[decisions-log#D034]]
- [ ] 409 Conflict response on `OptimisticLockException`, client retries after next WebSocket broadcast
- [ ] Spell test harness: unit tests for each pattern category with representative spells

**Frontend tasks:**
- [ ] "Cast Spell" button in player encounter panel (on their turn)
- [ ] Spell selection modal: list prepared spells, show components/concentration/casting time
- [ ] Slot level selector (for upcasting): shows available slots, highlights minimum level
- [ ] Target selector: validates target count for the spell, adjusts with upcast if targetCountUpcastScaling
- [ ] Auto-resolution result display in combat log (attack rolls, save results, damage dealt, conditions applied)
- [ ] Condition indicators showing source spell (e.g., "Restrained (Entangle)")
- [ ] DM spell casting for monsters with spellcasting stat blocks
- [ ] `requiresManualResolution` spells: deduct slot, log cast, show prompt for DM to resolve manually

## Milestone 12: Monster Actions, Legendary Actions, Legendary Resistance, Lair Actions

**Goal:** DM can click monster stat block actions and have them auto-resolve against targets.

**Backend tasks:**
- [ ] Parse monster stat block actions from M7 structured data (~1,300–1,600 monsters) into `MonsterActionTemplate` entities
- [ ] `MonsterActionResolverEngine` — interprets action templates (same effect engine as spells)
- [ ] `POST /api/encounters/{id}/combat/monster-action` endpoint: monsterParticipantId, actionName, targetParticipantIds
- [ ] Legendary action pool tracking: `legendaryActionsRemaining` field on `EncounterParticipant`, resets at start of monster's turn, decremented on use
- [ ] Legendary resistance tracking: `legendaryResistancesRemaining` field, DM can use to auto-succeed a failed save (inline override)
- [ ] Lair actions: auto-prompt at initiative count 20 (losing ties), DM selects from available lair actions
- [ ] Recharge mechanics: track which actions are available, roll recharge at start of monster's turn
- [ ] Multiattack: execute multiple actions in sequence from a single button press
- [ ] Monster spellcasting: extract spell lists and slots from stat block, use same SpellResolverEngine

**Frontend tasks:**
- [ ] DM monster action panel: list all actions from stat block with one-click resolve
- [ ] Legendary action buttons (with remaining count display, cost per action)
- [ ] Legendary resistance inline button: when a monster fails a save, show "Use Legendary Resistance?" prompt with remaining count
- [ ] Lair action prompt at initiative 20
- [ ] Recharge indicator on actions (available / needs recharge)
- [ ] Monster spellcasting panel (separate from action panel, shows available spells and slots — slots visible to DM only)
- [ ] Attack source selector: when DM attacks, optionally select which creature is doing the attack for clearer combat log entries

## Milestone 13: Enhanced Action Economy

**Goal:** Full D&D 5e action economy tracking with reactions, bonus actions, and non-attack actions.

**Backend tasks:**
- [ ] Action economy tracking per turn: action used, bonus action used, reaction used (reset on turn start)
- [ ] Reaction system: `POST /api/encounters/{id}/combat/reaction` — usable on any turn, consumes reaction
  - Opportunity attacks
  - Shield (reaction spell)
  - Counterspell (reaction spell with ability check for higher level)
  - Other reaction spells
- [ ] Bonus action tracking: enforce one per turn
- [ ] Bonus-action-spell rule (PHB 202): if a bonus action spell is cast, any other spell cast that turn must be a cantrip with casting time of 1 action
- [ ] Standard non-attack actions: Dodge, Help, Hide, Dash — log to combat log, apply relevant effects
  - Dodge: advantage on DEX saves, attacks against you have disadvantage (track as a condition-like state, expires at start of next turn)
  - Help: grant advantage on next attack or ability check against a target
  - Hide: contested Stealth check (log result, DM adjudicates)
  - Dash: double movement (informational log entry)
- [ ] Item use in combat:
  - `POST /api/encounters/{id}/combat/use-item` endpoint
  - Charge tracking and deduction
  - Attunement check (if item requires attunement, verify character is attuned)
  - Effect resolution through the same effect engine
  - Consumable items: removed from inventory on use
- [ ] Free object interaction: freetext logging for minor actions (drawing a weapon, opening a door)

**Frontend tasks:**
- [ ] Reaction prompt: when an event triggers a possible reaction (e.g., creature leaves reach), inline combat log prompt with available reactions
- [ ] Reaction counter display per participant
- [ ] Bonus action modal: show available bonus actions (bonus action spells, class abilities like Cunning Action)
- [ ] Standard action buttons: Dodge, Help, Hide, Dash in a burger menu
- [ ] Item use modal: list equipped/attuned items with charges, select item → resolve effect
- [ ] Free object interaction: freetext input logged to combat log
- [ ] Action economy indicators: visual display of action/bonus action/reaction availability per turn

## Milestone 14: Undo System

**Goal:** DM can undo any combat action, fully restoring prior state.

**Backend tasks:**
- [ ] Add `stateSnapshot` JSONB column to `CombatLog` entity — stores before-state of every affected participant (HP, conditions, concentration, death saves, spell slots, legendary actions/resistances remaining)
- [ ] Capture snapshot before every combat action (damage, healing, spell cast, condition add/remove, monster action, item use)
- [ ] Cascading effects (e.g., concentration drop → condition removal on multiple targets) captured in the outermost action's snapshot — see [[decisions-log#D037]]
- [ ] `POST /api/encounters/{id}/combat/undo` endpoint (DM-only): restores all participant states from the most recent log entry's snapshot, deletes the log entry
- [ ] Support multi-step undo (undo the last N actions)
- [ ] Undo clears any conditions, buffs, or effects that were applied by the undone action

**Frontend tasks:**
- [ ] DM-only "Undo Last Action" button in encounter session
- [ ] Confirmation dialog showing what will be undone
- [ ] Combat log entry removal on undo (with visual feedback)

## Milestone 15: Persistent Spell Effects as Companion Participants

**Goal:** Spells like Spiritual Weapon, Flaming Sphere, and summoned creatures appear as sub-cards beneath the caster in the initiative order.

**Backend tasks:**
- [ ] New `ParticipantType`: `COMPANION`
- [ ] `summonedByParticipantId` FK on `EncounterParticipant` linking companion to caster
- [ ] Auto-create companion participant when a SUMMON spell is cast
- [ ] Auto-remove companion when concentration drops or duration ends
- [ ] Companion actions resolve through the same effect engine
- [ ] Companion initiative: acts on caster's turn (or specific initiative as defined by spell)

**Frontend tasks:**
- [ ] Sub-card UI: companion participants rendered as indented cards beneath their caster
- [ ] Companion action buttons (e.g., Spiritual Weapon attack, Flaming Sphere ram)
- [ ] Visual link between companion and caster (concentration indicator)

## Milestone 16: Short/Long Rest System

**Goal:** Implement rest mechanics for resource recovery between encounters.

**Backend tasks:**
- [ ] `POST /api/characters/{id}/short-rest` — hit dice spending for HP recovery, feature/charge recovery (per short rest resources)
- [ ] `POST /api/characters/{id}/long-rest` — full HP recovery, hit dice recovery (half total, rounded down), all spell slot recovery, all feature/charge recovery (per long rest resources)
- [ ] Warlock Pact Magic: spell slots recover on short rest
- [ ] Resource tracking: charges and uses for class features, racial abilities, magic items

**Frontend tasks:**
- [ ] Short rest modal: hit dice spending interface (select number of hit dice to spend, show HP recovery preview, confirm)
- [ ] Long rest button with summary of what recovers
- [ ] Resource counters on character sheet (used/total for each tracked resource)

## Milestone 17: Class Feature Automation

**Goal:** Automate common class features used in combat encounters.

**Backend tasks:**
- [ ] Feature action endpoints: `POST /api/encounters/{id}/combat/use-feature`
- [ ] Fighter: Second Wind (bonus action heal), Action Surge (extra action)
- [ ] Cleric: Channel Divinity (class + subclass options), Turn Undead
- [ ] Rogue: Cunning Action (bonus action Dash/Disengage/Hide), Sneak Attack (extra damage, once per turn)
- [ ] Paladin: Divine Smite (extra damage on hit, uses spell slot), Lay on Hands (healing pool)
- [ ] Bard: Bardic Inspiration (bonus action, give ally a die to add to roll)
- [ ] Barbarian: Rage (bonus action, resistance to physical damage, extra melee damage)
- [ ] Monk: Ki Points, Flurry of Blows, Patient Defense, Step of the Wind
- [ ] Druid: Wild Shape (stat block swap)
- [ ] Wizard: Arcane Recovery (recover spell slots on short rest)
- [ ] Use/recharge tracking per feature

**Frontend tasks:**
- [ ] Feature buttons in encounter action panel (context-aware: show on correct turn, check uses remaining)
- [ ] Feature resource counters (Ki points, Bardic Inspiration uses, Channel Divinity uses, etc.)
- [ ] Divine Smite prompt on hit (choose spell slot level for extra damage)

## Milestone 18: Sorcerer Metamagic

**Goal:** Implement Sorcerer-specific metamagic options with Sorcery Point tracking.

**Backend tasks:**
- [ ] Sorcery Point tracking (pool size = sorcerer level)
- [ ] Metamagic options:
  - Twinned Spell: spend sorcery points = spell level to target a second creature
  - Quickened Spell: spend 2 points to cast as bonus action instead of action
  - Subtle Spell: spend 1 point to cast without verbal/somatic components
  - Heightened Spell: spend 3 points to give one target disadvantage on save
  - Other metamagic options as needed
- [ ] Font of Magic: convert sorcery points ↔ spell slots
- [ ] Validation: correct sorcery point cost, eligible spells for each metamagic

**Frontend tasks:**
- [ ] Metamagic toggle buttons when casting a spell (show eligible options)
- [ ] Sorcery Point counter on character sheet and in encounter panel
- [ ] Font of Magic UI: convert points to slots or slots to points

---

**Parallelism note:** M7 (data gathering) and the non-spell parts of M9 (character builder) can run in parallel — they have no dependencies on each other. M9's Spells tab is blocked by M7/M8 completion. M10 (character leveling) depends on M9 (character builder) and the M7 class feature analysis (levels 1–5, to be extended to 1–20). M11 depends on M7/M8 (spell data) and M9 (character with spell lists). M12 depends on M7 (monster action data). M13 and M14 depend on M11/M12. M15–M18 depend on M11 being complete. M10 and M11 have no dependency on each other and can run in parallel.

## Future Features (Post Month 1)

These are documented for future reference and explicitly **not in scope** for the current build.

- Homebrew monster creator with CR calculator
- Homebrew item creator
- Loot generator (random tables from DMG)
- Campaign notes (markdown editor, Obsidian-style linking)
- Filtered bestiary for players (DM assigns creature knowledge per player)
- Character import (D&D Beyond JSON export, etc.)
- Non-combat encounter support (skill challenges, social encounters)
- Multi-system support (Pathfinder 2e, Lancer, Shadowrun rule modules)
- Map/grid integration (simple grid overlay for tactical movement)
- Ambient sound/music integration
- Dice rolling animations (3D dice)
- Session history (view past encounters and combat logs)

## Planned UX Improvements

- ~~**Character creation constraints**~~ — **Covered by M9.** Race/class/subclass dropdowns, ability score methods, derived stat auto-calculation. See [[feature-roadmap#Milestone 9: Character Builder Overhaul]].
- ~~**Graceful stale token handling**~~ — **Done.** Axios interceptor now catches 401 and 403, silently refreshes using the refresh token, queues concurrent requests, and redirects to login only when the refresh token is also expired. See [[decisions-log#D017]].
