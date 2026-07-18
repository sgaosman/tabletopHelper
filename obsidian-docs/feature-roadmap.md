# Feature Roadmap

## Milestone Status

| # | Milestone | Status | Notes |
|---|-----------|--------|-------|
| 1 | Project Setup & Authentication | Complete | Auth, JWT, login/register UI |
| 2 | Campaign Management & Character Sheets | Complete | Campaigns, invite codes, character CRUD |
| 3 | 5e.tools Data Import & Reference Browsing | Complete | Bestiary, spells, items, conditions, quick rules reference |
| 4 | Encounter Builder & WebSocket Setup | Complete | Encounter CRUD, participant management, WebSocket real-time sync, multiselect filters |
| 5 | Combat Engine | Complete | Full combat: damage, healing, conditions (with duration tracking), death saves, concentration, attack rolls, spell slot tracking, turn-based auto-expiry |
| 6 | Polish, Mobile & Deployment | Not started | |

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

## Milestone 6: Polish, Mobile & Deployment

**Tasks:**
- [ ] Mobile-responsive encounter screens
- [ ] Error handling — toast notifications, WebSocket disconnect/reconnect
- [ ] Loading states for all async operations
- [ ] Dockerfiles (backend + frontend)
- [ ] docker-compose.prod.yml
- [ ] Nginx configuration (reverse proxy + SSL)
- [ ] Deploy to Hetzner VPS or Railway
- [ ] End-to-end testing with real devices

## Future Features (Post Month 1)

These are documented for future reference and explicitly **not in scope** for the initial build.

- Homebrew monster creator with CR calculator
- Homebrew item creator
- Loot generator (random tables from DMG)
- Campaign notes (markdown editor, Obsidian-style linking)
- Filtered bestiary for players (DM assigns creature knowledge per player)
- Character import (D&D Beyond JSON export, etc.)
- Companion/minion management in encounters (familiars, summoned creatures)
- Non-combat encounter support (skill challenges, social encounters)
- Multi-system support (Pathfinder 2e, Lancer, Shadowrun rule modules)
- Map/grid integration (simple grid overlay for tactical movement)
- Ambient sound/music integration
- Dice rolling animations (3D dice)
- Session history (view past encounters and combat logs)

## Planned UX Improvements

- **Character creation constraints** — Replace free-text inputs with dropdowns for race, class, subclass (populated from seeded 5e data). Offer point buy, standard array, and 4d6-drop-lowest for ability scores. Auto-calculate derived stats like proficiency bonus from level. See [[risk-register#R003]] for details.
- ~~**Graceful stale token handling**~~ — **Done.** Axios interceptor now catches 401 and 403, silently refreshes using the refresh token, queues concurrent requests, and redirects to login only when the refresh token is also expired. See [[decisions-log#D017]].
