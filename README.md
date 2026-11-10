# TabletopHelper

A web application for running tabletop RPG sessions, starting with D&D 5th Edition (2014). TabletopHelper combines a comprehensive rules reference database, player character tracking, and a real-time encounter engine into a single tool designed for one DM and up to 8 players.

## What It Does

TabletopHelper replaces the scattered collection of PDFs, paper notes, and browser tabs that most D&D groups use to run combat. The DM and players connect to a shared encounter session where initiative, HP, conditions, attacks, spells, and death saves are all tracked in real time across every device at the table.

### Features Available Now

- **User accounts** — Register, log in, choose between DM and Player roles each session; silent JWT token refresh keeps sessions alive
- **Campaign management** — DMs create campaigns and share an invite code; players join with the code
- **Character creation wizard** — Guided 8-step wizard (modular step components with localStorage draft saving and beforeunload guard): race (226 races with ASI preview, Tasha's reassignment), class (13 classes, 124 subclasses), ability scores (standard array, point buy, manual), background (101 backgrounds with equipment and proficiency pickers including exotic languages, feat detection for 15 feat-granting backgrounds), starting equipment (class and background choices or starting gold, with a live AC preview), spells (cantrip/spell selection for casters, feat spell selection for non-casters with spell-granting feats), alignment, review. Multiclass support with PHB spell slot calculation. Auto-calculated spell slots, save DC, and attack bonus. 1/3 caster support (Eldritch Knight, Arcane Trickster)
- **Character leveling** — Level up and level down with full multiclass support. PHB prerequisite validation for multiclassing. ASI/feat/subclass choices recorded in level history for deterministic rollback. HP auto-calculation with class hit dice. Proficiency bonus auto-update. Spell slot recalculation for single-class, multiclass, and pact magic casters
- **Feat automation** — 29 feats with structured mechanical effects: ability score increases, proficiencies (armor, weapon, tool, skill, language, saving throw), expertise, resistances, speed bonuses, initiative bonuses, HP per level, passive perception/investigation bonuses, and limited-use resources (e.g. Lucky's 3 Luck Points). Effects applied on level-up and deterministically reversed on level-down
- **Character sheets** — Six-tab layout (Stats, Actions, Spells, Inventory, Features, Journal) with full D&D 5e stats; skill proficiency indicators (colored bullets, expertise stars); armor/weapon/tool/language proficiency display; short rest with multi-dice spending (per-class hit dice selector) and warlock pact slot reset; long rest with full HP/slot/resource recovery; campaign assignment dropdown; character deletion with confirmation modal and active-combat protection
- **Spell management** — Spells tab with source-grouped display: per-class boxes (prepared/known badges, preparation limit enforcement), race innate spell box, feat spell boxes with add/remove. Spell detail modal (full SpellCard). "Change Prepared" and "Manage Known" modals with class-filtered search, always-prepared subclass spells (lock icon), max spell level enforcement. "Add Feat Spells" modal for post-creation feat acquisition (searchable feat list, option/ability/spell configuration). Wizard spellbook management (add/remove spells, prepare from spellbook only, 6 + 2/level starting spells). Wizard cantrip swapping from level 3+ (Cantrip Formulas). Non-caster classes with feat spells show only feat boxes, not an empty class section (Eldritch Knight/Arcane Trickster excluded)
- **DM campaign view** — DMs see all members and characters in their campaigns, auto-refreshing every 10 seconds
- **Full 5e reference database** — Searchable bestiary (2000+ monsters), spells (500+), items (1000+), and conditions from every 2014-era D&D 5e sourcebook, with multiselect filters and quick rules reference
- **Encounter builder** — DMs create encounters by adding monsters (with fuzzy search, typo-tolerant) and player characters from their campaign; manual or auto-rolled initiative; participant renaming while preserving monster identity
- **Live encounter sessions** — Real-time WebSocket sync between DM and players via STOMP over SockJS; session codes for players to join; initiative tracking, turn management, pause/resume/end controls
- **Combat engine** — Full D&D 5e combat:
  - Attack rolls (d20 vs AC) with advantage/disadvantage, critical hits (nat 20 doubles dice), force-crit toggle, multi-attack (up to 5 attack rolls at once)
  - Damage and healing with temp HP absorption, damage types, concentration checks
  - Unconscious combat rules: attacks auto-hit downed PCs (default advantage), damage causes death save failures (2 on crit), massive damage (>= max HP) instant kills
  - Death saves, stabilization, revival; resurrection of dead PCs (3 failed saves) with auto-prone
  - Condition tracking with durations and auto-expiry at start of turn
  - Concentration tracking with auto-drop on 0 HP and DC-based checks on damage
  - Spell slot tracking per encounter (copied from character sheet on join)
  - Combat log with round/turn headers, colour-coded entries, smart scroll (stays in place when reading history, "scroll to bottom" indicator for new messages)
  - Action economy: actions, bonus actions, reactions and object interactions per turn; Dash, Disengage, Dodge, Help, Hide; the bonus-action spell rule; opportunity attacks and two-weapon fighting
  - Conditions set advantage and disadvantage automatically (restrained, prone, blinded, invisible and the rest of PHB appendix A), with blindsight and truesight respected
  - DM undo of any combat action, several at once, including Next Turn
  - Lasting spells (Spiritual Weapon, Moonbeam, Spirit Guardians and others) shown under their caster, with reminders for area damage
  - Summoning: Conjure Animals, the Summon Beast/Fey/Shadowspawn/Undead spirits, Summon Lesser Demons, Find Familiar, Find Steed, Animate Dead and Tiny Servant add their creatures to the encounter
  - Monster actions, multiattack, legendary and lair actions, legendary resistance, recharge and per-day limits
  - Every spell from cantrips to 9th level has its rules built in: damage, saves, conditions, lasting areas, summons and concentration, with the rest flagged for the DM
  - Spell buffs and debuffs change the rolls while they last (Bless, Bane, Shield of Faith, Haste, Faerie Fire, Hunter's Mark and many more), and the DM can mark a spell as already active from before the fight
  - Exhaustion tracked by level with all its effects, and Evasion for rogues, monks and monsters
- **Class resource tracking** — Ki Points, Action Surge, Channel Divinity, Bardic Inspiration, Wild Shape, Rage, Sorcery Points and every other limited-use class, feat and monster resource, tracked on the sheet and spent in encounters, with the features automated: Rage and Reckless Attack, Second Wind, Lay on Hands, Divine Smite, Sneak Attack, Martial Arts and Flurry of Blows, Stunning Strike, fighting styles, Channel Divinity options, Bardic Inspiration, Arcane Recovery, Wild Shape (Elemental Wild Shape included), Font of Magic, all eight PHB Metamagic options and the warlock's Mystic Arcanum
- **Armour and armour class** — AC worked out from equipped armour and shield, Unarmored Defense and natural armour, with Strength requirements, Stealth disadvantage and the penalties for armour without proficiency
- **Glossary** — 104 game terms in rules text open a plain-English definition when clicked
- **Phone-friendly encounter screens** — players use their phones at the table and the DM a laptop; error notifications and an automatic reconnect banner

### Architecture & Performance

- **Reference data caching** — `@Cacheable` on 13 reference endpoints (races, classes, subclasses, backgrounds, feats, spell metadata) eliminates repeated DB reads for static data
- **Database indexes** — FK indexes on all frequently queried columns (user_id, campaign_id, encounter_id, character_id) plus a GIN index on spells.classes for JSONB containment queries
- **Input validation** — `@Valid` constraints on character update requests (level 1-20, ability scores 1-30, HP/speed bounds, name length)
- **Tests** — about 3,000 backend unit tests, 400 integration tests against a real PostgreSQL (Testcontainers) and 1,100 frontend tests (Vitest), run in CI
- **Structured logging** — All exception handlers log with context instead of silently swallowing errors

### Features Coming Soon

- **Hosting** — putting the app online, with HTTPS and nightly backups, once a host is chosen

### Future Roadmap (Post-Launch)

- Homebrew monster and item creation with CR calculator
- Loot generator from DMG tables
- Campaign notes with markdown editing
- Character import from external sources
- Multi-system support (Pathfinder 2e, Lancer, Shadowrun)
- Map/grid integration for tactical movement

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend | Java 21, Spring Boot 4.1, Jackson 3, Hibernate 7, Flyway, Gradle 9 (Kotlin DSL) |
| Frontend | React 19, TypeScript, Vite 8, Tailwind CSS 4 |
| Database | PostgreSQL 16 (Docker) |
| Real-time | WebSocket with STOMP over SockJS |
| Auth | JWT access tokens plus a rotating, HttpOnly refresh cookie (D156) |

## Getting Started

### Prerequisites

- Java 21 (JDK)
- Node.js 24 (see `frontend/.nvmrc`)
- Docker and Docker Compose

### Setup

```bash
# Start PostgreSQL
docker compose up -d db

# Start the backend (in one terminal)
cd backend
./gradlew bootRun --args='--spring.profiles.active=dev'

# Start the frontend (in another terminal)
cd frontend
npm install
npm run dev
```

The app is available at:
- **Frontend:** http://localhost:5173
- **Backend API:** http://localhost:8080/api
- **Swagger UI:** http://localhost:8080/swagger-ui.html

## Documentation

Internal documentation for developers is in the [`obsidian-docs/`](obsidian-docs/) directory, covering architecture, API reference, database schema, decisions log, risk register, and troubleshooting.
