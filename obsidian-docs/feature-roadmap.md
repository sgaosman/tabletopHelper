# Feature Roadmap

## Milestone Status

| # | Milestone | Status | Notes |
|---|-----------|--------|-------|
| 1 | Project Setup & Authentication | Complete | Auth, JWT, login/register UI |
| 2 | Campaign Management & Character Sheets | Complete | Campaigns, invite codes, character CRUD |
| 3 | 5e.tools Data Import & Reference Browsing | Complete | Bestiary, spells, items, conditions, quick rules reference |
| 4 | Encounter Builder & WebSocket Setup | Complete | Encounter CRUD, participant management, WebSocket real-time sync, multiselect filters |
| 5 | Combat Engine | Not started | Core feature |
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

**Frontend tasks:**
- [x] TypeScript interfaces for Encounter, EncounterParticipant, all request/response types
- [x] encounterApi — REST client for all encounter endpoints
- [x] useWebSocket hook — STOMP connection lifecycle, JWT auth, subscriptions, auto-reconnect (5s delay)
- [x] EncounterContext — live encounter state from WebSocket + REST fallback
- [x] EncounterBuilderPage — campaign selector, encounter list with status badges, create form, add PCs from campaign, search/add monsters with quantity, roll initiative, start encounter
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

**Tasks:**
- [ ] CombatService — processAttack, processSpellCast, processHeal, applyDamage, applyCondition, removeCondition, processDeathSave, advanceTurn, checkConcentration
- [ ] CombatWebSocketController with @MessageMapping handlers
- [ ] DiceRoller utility (server-side randomness)
- [ ] Attack flow — roll → compare AC → apply damage → broadcast
- [ ] Spell flow — save-based and attack-based, spell slot deduction, concentration
- [ ] Healing flow
- [ ] Condition application/removal with duration tracking
- [ ] Death saving throws
- [ ] Concentration checks on damage
- [ ] Temporary HP (damage reduces temp first, doesn't stack)
- [ ] Turn management (advance/back, start-of-turn effects)
- [ ] CombatLog entity — human-readable action log
- [ ] Frontend: ActionPanel, InitiativeTracker, ParticipantPanel, CombatLog, HpBar, ConditionBadges, DiceRoller
- [ ] Permission enforcement — players control own characters only, DM controls monsters + overrides

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
- **Graceful stale token handling** — Improve the Axios 403 interceptor to detect "token present but rejected" and redirect to login instead of showing a generic error. Extremely low priority — only affects dev restarts or sessions idle for 7+ days. See [[risk-register#R005]].
