# Database Schema

PostgreSQL 16, accessed via Spring Data JPA with Hibernate 6. All IDs are UUIDs. All entities have `created_at` and `updated_at` timestamps. JSON fields use PostgreSQL `jsonb` column type.

## Entity Relationship Diagram

```
┌──────────────┐       ┌───────────────────┐       ┌──────────────────┐
│    users     │       │    campaigns      │       │ player_characters│
├──────────────┤       ├───────────────────┤       ├──────────────────┤
│ id (PK)      │◄──┐   │ id (PK)           │   ┌──►│ id (PK)          │
│ username     │   │   │ name              │   │   │ name             │
│ email        │   │   │ description       │   │   │ race             │
│ password_hash│   ├───┤ dm_user_id (FK)   │   │   │ character_class  │
│ display_name │   │   │ invite_code       │   │   │ level            │
│ created_at   │   │   │ is_active         │   │   │ strength ...     │
│ updated_at   │   │   │ created_at        │   │   │ hp_max, hp_cur   │
└──────────────┘   │   │ updated_at        │   │   │ armour_class     │
                   │   └───────────────────┘   │   │ [JSON fields]    │
                   │            │               │   │ resource_pools   │── NEW
                   │            │               │   │ user_id (FK) ────┤──► users
                   │            │               │   │ campaign_id (FK) ┤──► campaigns
                   │   ┌────────▼──────────┐   │   │ created_at       │
                   │   │ campaign_members  │   │   │ updated_at       │
                   │   ├───────────────────┤   │   └──────────────────┘
                   │   │ id (PK)           │   │
                   ├───┤ user_id (FK)      │   │
                   │   │ campaign_id (FK)  │   │
                   │   │ role (DM/PLAYER)  │   │
                   │   │ joined_at         │   │
                   │   │ UNIQUE(camp, user)│   │
                   │   └──────────────────-┘   │
                   │                           │
                   │   ┌───────────────────┐   │
                   │   │    encounters     │   │
                   │   ├───────────────────┤   │
                   │   │ id (PK)           │   │
                   │   │ campaign_id (FK)  │───┘
                   │   │ name, status      │
                   │   │ round_number      │
                   │   │ current_turn_idx  │
                   │   │ session_code      │
                   │   └───────┬───────────┘
                   │           │
                   │   ┌───────▼───────────────┐
                   │   │encounter_participants │
                   │   ├───────────────────────┤
                   │   │ id (PK)               │
                   │   │ encounter_id (FK)      │
                   │   │ character_id (FK)      │──► player_characters
                   │   │ monster_id (FK)        │──► monsters
                   │   │ participant_type       │
                   │   │ initiative, hp, ac     │
                   │   │ active_conditions JSON │
                   │   │ resource_pools_current │── NEW
                   │   │ controlled_by (FK) ────┤──► users
                   │   └───────────────────────┘
                   │
                   │   ┌───────────────┐  ┌──────────┐  ┌────────┐  ┌──────────┐
                   │   │   monsters    │  │  spells  │  │ items  │  │conditions│
                   │   │  (seeded M3)  │  │(seeded)  │  │(seeded)│  │ (seeded) │
                   └───┤ created_by_   │  └──────────┘  └────────┘  └──────────┘
                       │  user_id (FK) │
                       └───────────────┘
```

## Current Tables (Milestones 1-2)

### users

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | UUID | PK, auto-generated | |
| username | VARCHAR(50) | UNIQUE, NOT NULL | |
| email | VARCHAR(255) | UNIQUE, NOT NULL | |
| password_hash | VARCHAR(255) | NOT NULL | BCrypt encoded |
| display_name | VARCHAR(100) | | Defaults to username |
| created_at | TIMESTAMPTZ | | Auto-set |
| updated_at | TIMESTAMPTZ | | Auto-updated |

### campaigns

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | UUID | PK | |
| name | VARCHAR(200) | NOT NULL | |
| description | TEXT | | |
| dm_user_id | UUID | FK → users, NOT NULL | |
| invite_code | VARCHAR(8) | UNIQUE, NOT NULL | Random alphanumeric, excludes ambiguous chars (0/O, 1/I/L) |
| is_active | BOOLEAN | DEFAULT TRUE | |
| created_at | TIMESTAMPTZ | | |
| updated_at | TIMESTAMPTZ | | |

### campaign_members

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | UUID | PK | |
| campaign_id | UUID | FK → campaigns, NOT NULL | |
| user_id | UUID | FK → users, NOT NULL | |
| role | VARCHAR | NOT NULL, CHECK (DM, PLAYER) | |
| joined_at | TIMESTAMPTZ | | |
| | | UNIQUE(campaign_id, user_id) | Prevents duplicate membership |

### player_characters

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | UUID | PK | |
| user_id | UUID | FK → users, NOT NULL | Character owner |
| campaign_id | UUID | FK → campaigns, NULLABLE | NULL = unassigned |
| name | VARCHAR(200) | NOT NULL | |
| race | VARCHAR(100) | | |
| character_class | VARCHAR(100) | | Avoids reserved word `class` |
| subclass | VARCHAR(100) | | |
| level | INTEGER | DEFAULT 1 | |
| experience_points | INTEGER | DEFAULT 0 | |
| background | VARCHAR(100) | | |
| alignment | VARCHAR(50) | | |
| strength - charisma | INTEGER | NOT NULL | Six ability scores |
| hp_max | INTEGER | NOT NULL | |
| hp_current | INTEGER | NOT NULL | |
| hp_temp | INTEGER | DEFAULT 0 | |
| hit_dice_total | VARCHAR(20) | | e.g. "8d10" — **DEPRECATED**, superseded by `resource_pools` |
| hit_dice_remaining | VARCHAR(20) | | **DEPRECATED**, superseded by `resource_pools` |
| armour_class | INTEGER | NOT NULL | |
| initiative_bonus | INTEGER | DEFAULT 0 | |
| speed | INTEGER | DEFAULT 30 | |
| proficiency_bonus | INTEGER | DEFAULT 2 | |
| saving_throw_proficiencies | JSONB | | Array of ability names |
| skill_proficiencies | JSONB | | Array of skill names |
| skill_expertises | JSONB | | |
| armor_proficiencies | JSONB | | Array of armor proficiency names |
| weapon_proficiencies | JSONB | | Array of weapon proficiency names |
| tool_proficiencies | JSONB | | Array of tool proficiency names |
| language_proficiencies | JSONB | | Array of language names |
| damage_resistances | JSONB | | |
| damage_immunities | JSONB | | |
| condition_immunities | JSONB | | |
| features | JSONB | | Array of {name, description, uses} |
| spells_known | JSONB | | |
| spell_slots | JSONB | | {1: {max, remaining}, ...} |
| spell_save_dc | INTEGER | | |
| spell_attack_bonus | INTEGER | | |
| spellcasting_ability | VARCHAR(20) | | |
| equipment | JSONB | | |
| currency | JSONB | | {cp, sp, ep, gp, pp} |
| personality_traits | TEXT | | |
| ideals | TEXT | | |
| bonds | TEXT | | |
| flaws | TEXT | | |
| notes | TEXT | | |
| death_save_successes | INTEGER | DEFAULT 0 | |
| death_save_failures | INTEGER | DEFAULT 0 | |
| portrait_url | VARCHAR(500) | | |
| race_id | UUID | FK → races, NULLABLE | Reference to seeded race |
| class_id | UUID | FK → character_classes, NULLABLE | Reference to seeded class |
| subclass_id | UUID | FK → subclasses, NULLABLE | Reference to seeded subclass |
| background_id | UUID | FK → backgrounds, NULLABLE | Reference to seeded background |
| ability_score_method | VARCHAR(20) | | standard, pointbuy, manual |
| racial_ability_bonuses | JSONB | | Snapshot of race ability bonuses |
| multiclass_entries | JSONB | | Array of {classId, className, level} |
| prepared_spells | JSONB | DEPRECATED — not used by DTOs | Legacy column, superseded by `spells_known[].prepared` |
| attuned_items | JSONB | | Array of attuned item names |
| equipped_items | JSONB | | Array of equipped item names |
| hit_dice_map | JSONB | **DEPRECATED** — superseded by `resource_pools` | Per-class hit dice: {className: {total, remaining, faces}} |
| level_history | JSONB | | Array of per-level entries for deterministic rollback |
| feat_resources | JSONB | **DEPRECATED** — superseded by `resource_pools` | Array of feat resource entries |
| **resource_pools** | **JSONB** | | **NEW (M24.5).** Array of ResourcePoolEntry objects. See the [Resource Pool reference tables](#resource-pool-tables-m245) section for schema. Subsumes `feat_resources` and `hit_dice_map`. |
| is_active | BOOLEAN | DEFAULT TRUE | Soft delete |
| created_at | TIMESTAMPTZ | | |
| updated_at | TIMESTAMPTZ | | |

### player_characters resource_pools Format

An array of ResourcePoolEntry objects. Each entry:

```json
{
  "poolId": "class:monk-ki",
  "displayName": "Ki Points",
  "sourceType": "CLASS",
  "sourceName": "Monk",
  "maxUses": 5,
  "currentUses": 3,
  "resetOn": "shortRest",
  "resetAmount": null,
  "resetCheck": null,
  "spendActionType": "FREE",
  "icon": "zap",
  "metadata": {}
}
```

- `maxUses` is the evaluated integer (evaluated from `maxUsesFormula` at creation/level-up).
- `maxUsesFormula` is the raw expression (stored for reference, not used at read time).
- `resetAmount` is an expression string for partial recovery: `"ceil(level/2)"`, `"floor(maxUses/2)"`, `"1"`. NULL = full reset to maxUses.
- `resetCheck` is a recharge expression: `"1d6>=5"` for probabilistic recovery.

---

## Reference Tables (Milestone 3 — Seeded from 5e.tools)

These tables are populated automatically on startup by `DataSeeder` if empty. Data comes from the 2014 branch of 5e.tools.

### monsters

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| name | VARCHAR | |
| type | VARCHAR | e.g. "Aberration", "Dragon". Handles nested `choose` patterns |
| size | VARCHAR | |
| alignment | VARCHAR | |
| armour_class | INTEGER | |
| hit_points | INTEGER | |
| hit_dice | VARCHAR | |
| speed | JSONB | `@JsonRawValue` — raw JSON object |
| strength - charisma | INTEGER | Six ability scores |
| saving_throws | JSONB | |
| skills | JSONB | |
| damage_resistances - condition_immunities | JSONB | |
| senses | JSONB | |
| languages | VARCHAR | |
| challenge_rating | VARCHAR | e.g. "1/2", "5", "30" |
| experience_points | INTEGER | |
| traits, actions, reactions, legendary_actions, lair_actions | JSONB | `@JsonRawValue` |
| source | VARCHAR | e.g. "MM", "VGM" |
| is_homebrew | BOOLEAN | |
| created_at | TIMESTAMPTZ | |

**Count:** 2,684 monsters

### spells

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| name | VARCHAR | |
| level | INTEGER | 0 = cantrip |
| school | VARCHAR | e.g. "Evocation" |
| casting_time, range_distance, duration | VARCHAR | |
| components | JSONB | `@JsonRawValue` |
| concentration, ritual | BOOLEAN | |
| description, higher_levels | TEXT | |
| classes | JSONB | `@JsonRawValue` — array including subclass entries like "Cleric (Knowledge)" |
| damage_type, damage_dice, save_ability | VARCHAR | |
| effect_template | JSONB | `@JsonRawValue` — structured spell effect definition |
| source | VARCHAR | |
| created_at | TIMESTAMPTZ | |

**Count:** 525 spells (288 with effect templates, ~184 auto-resolvable)

### items

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| name | VARCHAR | |
| type | VARCHAR | Resolved via cascade: TYPE_MAP → typeAlt → baseItem → boolean flags → description inference |
| subtype | VARCHAR | Weapon category |
| rarity | VARCHAR | |
| description | TEXT | |
| properties | JSONB | `@JsonRawValue` |
| requires_attunement | BOOLEAN | |
| attunement_condition | VARCHAR | |
| weight | DOUBLE | |
| cost | VARCHAR | Formatted from copper value |
| damage_dice, damage_type | VARCHAR | |
| source | VARCHAR | |
| is_homebrew | BOOLEAN | |
| created_at | TIMESTAMPTZ | |

**Count:** 1,723 items (zero NULL types)

### conditions

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| name | VARCHAR | |
| description | TEXT | Includes table data (e.g. exhaustion levels) |
| effects | JSONB | `@JsonRawValue` — bullet point effects |
| source | VARCHAR | |
| created_at | TIMESTAMPTZ | |

**Count:** 15 conditions

## Character Builder Reference Tables (Milestone 9 — Seeded from 5e.tools)

### races

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| name | VARCHAR(200) | NOT NULL. Subraces stored as separate rows (e.g. "High Elf") |
| source | VARCHAR(100) | |
| size | VARCHAR(20) | "Medium", "Small", "Small/Medium" |
| speed | JSONB | `@JsonRawValue` — int or {walk, fly, swim, ...} |
| ability_score_bonuses | JSONB | `@JsonRawValue` — [{ability, bonus}] |
| creature_type | VARCHAR(50) | |
| darkvision | INTEGER | In feet, null if none |
| traits | JSONB | `@JsonRawValue` — [{name, description}] |
| proficiencies | JSONB | `@JsonRawValue` — {skills, languages, weapons, armor, tools} |
| resistances | JSONB | `@JsonRawValue` — [string] |
| race_choices | JSONB | `@JsonRawValue` — structured race choice options |
| additional_spells | JSONB | `@JsonRawValue` — normalized race spellcasting data |
| base_race_name | VARCHAR(200) | Set for subraces (e.g. "Elf") |
| description | TEXT | |
| created_at | TIMESTAMPTZ | |

**Count:** 226 races

### character_classes

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| name | VARCHAR(100) | NOT NULL |
| source | VARCHAR(100) | |
| hit_dice | INTEGER | e.g. 8, 10, 12 |
| primary_ability | VARCHAR(50) | |
| saving_throw_proficiencies | JSONB | `@JsonRawValue` |
| armor_proficiencies | JSONB | `@JsonRawValue` |
| weapon_proficiencies | JSONB | `@JsonRawValue` |
| tool_proficiencies | JSONB | `@JsonRawValue` |
| skill_choices | JSONB | `@JsonRawValue` — {count, from: []} |
| spellcasting_ability | VARCHAR(20) | null for non-casters |
| is_spellcaster | BOOLEAN | |
| is_prepared_caster | BOOLEAN | |
| is_known_caster | BOOLEAN | |
| is_pact_magic | BOOLEAN | |
| spell_slot_progression | JSONB | `@JsonRawValue` |
| features | JSONB | `@JsonRawValue` — [{level, name, description}] |
| starting_equipment | JSONB | `@JsonRawValue` |
| subclass_level | INTEGER | Level at which subclass is chosen |
| multiclass_requirements | JSONB | PHB multiclass prerequisites |
| multiclass_proficiencies | JSONB | |
| created_at | TIMESTAMPTZ | |

**Count:** 13 classes

### subclasses

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| name | VARCHAR(200) | NOT NULL |
| source | VARCHAR(100) | |
| character_class_id | UUID | FK → character_classes, NOT NULL |
| features | JSONB | `@JsonRawValue` — [{level, name, description}] |
| always_prepared_spells | JSONB | `@JsonRawValue` |
| expanded_spell_list | JSONB | `@JsonRawValue` |
| additional_proficiencies | JSONB | `@JsonRawValue` |
| created_at | TIMESTAMPTZ | |

**Count:** 124 subclasses

### backgrounds

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| name | VARCHAR(200) | NOT NULL |
| source | VARCHAR(100) | |
| skill_proficiencies | JSONB | `@JsonRawValue` |
| tool_proficiencies | JSONB | `@JsonRawValue` |
| language_proficiencies | JSONB | `@JsonRawValue` |
| starting_equipment | JSONB | `@JsonRawValue` |
| feature | JSONB | `@JsonRawValue` — {name, description} |
| feats | JSONB | `@JsonRawValue` — array of feat name strings |
| description | TEXT | |
| created_at | TIMESTAMPTZ | |

**Count:** 101 backgrounds

### feats

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| name | VARCHAR(200) | NOT NULL |
| source | VARCHAR(100) | |
| prerequisite | JSONB | `@JsonRawValue` |
| description | TEXT | |
| ability_score_increase | JSONB | `@JsonRawValue` |
| grants_features | JSONB | `@JsonRawValue` |
| effects | JSONB | `@JsonRawValue` — structured mechanical effects. Contains any of: `resistances`, `expertise`, `armorProficiencies`, `weaponProficiencies`, `toolProficiencies`, `skillProficiencies`, `languageProficiencies`, `savingThrowProficiencies`, `optionalFeatureProgression`, `speedBonus`, `initiativeBonus`, `hpPerLevel`, `passivePerceptionBonus`, `passiveInvestigationBonus`, `resource` (with `name`, `maxUses`, `resetOn`). |
| created_at | TIMESTAMPTZ | |

**Count:** 108 feats

### optional_features

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| name | VARCHAR(200) | NOT NULL |
| source | VARCHAR(100) | |
| feature_type | VARCHAR(50) | Normalized type: `EldritchInvocation`, `Metamagic`, `BattleManeuver`, `FightingStyle` |
| description | TEXT | |
| prerequisite | JSONB | `@JsonRawValue` |
| created_at | TIMESTAMPTZ | |

**Count:** 114 optional features

## Encounter Tables (Milestone 4)

### encounters

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | UUID | PK | |
| campaign_id | UUID | FK → campaigns, NOT NULL | |
| name | VARCHAR(200) | NOT NULL | |
| description | TEXT | | |
| status | VARCHAR | NOT NULL, DEFAULT 'PREPARING' | PREPARING, ACTIVE, PAUSED, COMPLETED |
| current_turn_index | INTEGER | DEFAULT 0 | Index into sorted participants |
| round_number | INTEGER | DEFAULT 1 | |
| session_code | VARCHAR(8) | UNIQUE, NULLABLE | Generated on start, same charset as invite codes |
| created_at | TIMESTAMPTZ | | |
| updated_at | TIMESTAMPTZ | | |

### encounter_participants

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | UUID | PK | |
| encounter_id | UUID | FK → encounters, NOT NULL | |
| participant_type | VARCHAR | NOT NULL | PLAYER, MONSTER, COMPANION |
| character_id | UUID | FK → player_characters, NULLABLE | Set for PLAYER type |
| monster_id | UUID | FK → monsters, NULLABLE | Set for MONSTER type |
| display_name | VARCHAR(200) | NOT NULL | e.g. "Goblin 2" |
| initiative | INTEGER | NULLABLE | Set before encounter starts |
| initiative_modifier | INTEGER | DEFAULT 0 | Auto-populated from entity |
| sort_order | INTEGER | DEFAULT 0 | Based on initiative desc |
| hp_max | INTEGER | NOT NULL | |
| hp_current | INTEGER | NOT NULL | |
| hp_temp | INTEGER | DEFAULT 0 | |
| armour_class | INTEGER | NOT NULL | |
| active_conditions | JSONB | | Array of condition objects |
| concentration_spell | VARCHAR(200) | | Name of spell being concentrated on |
| concentration_slot_level | INTEGER | | Slot level used to cast the concentration spell |
| active_spell | VARCHAR(200) | | Non-concentration persistent spell |
| active_spell_slot_level | INTEGER | | Slot level used to cast the active spell |
| spell_slots_current | JSONB | | Copied from character on encounter join. |
| spell_attack_bonus | INTEGER | | Copied from character on encounter join |
| spell_save_dc | INTEGER | | Copied from character on encounter join |
| spellcasting_ability | VARCHAR(20) | | Copied from character on encounter join |
| spells_known | JSONB | | Copied from character on encounter join |
| **resource_pools_current** | **JSONB** | | **NEW (M24.5).** Array of ResourcePoolEntry objects. Copied from character sheet on join for PCs; generated from monster pool triggers for monsters. |
| is_visible_to_players | BOOLEAN | DEFAULT TRUE | |
| is_alive | BOOLEAN | DEFAULT TRUE | |
| is_current_turn | BOOLEAN | DEFAULT FALSE | |
| controlled_by_user_id | UUID | FK → users, NULLABLE | For PLAYER, set to character owner |
| death_save_successes | INTEGER | DEFAULT 0 | |
| death_save_failures | INTEGER | DEFAULT 0 | |
| notes | TEXT | | DM-only notes |
| action_used | BOOLEAN | DEFAULT FALSE | Action economy — separate from pool system |
| bonus_action_used | BOOLEAN | DEFAULT FALSE | Action economy — separate from pool system |
| reaction_used | BOOLEAN | DEFAULT FALSE | Action economy — separate from pool system |
| created_at | TIMESTAMPTZ | | |
| updated_at | TIMESTAMPTZ | | |

### encounter_participants resource_pools_current Format

Same `ResourcePoolEntry` JSONB array format as `player_characters.resource_pools`. During combat, `currentUses` is tracked independently from the character sheet. Per-turn resets (`resetOn == "turn"`) and recharge checks (`resetCheck`) are evaluated by `advanceTurn()`. On encounter COMPLETED, currentUses values are synced back to the character sheet for matching `poolId`s.

## Resource Pool Tables (M24.5)

Seeded reference tables populated on startup by `ResourcePoolSeeder`. These define what resource pools exist and which class features / monster abilities grant them.

### resource_pool_definitions

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| pool_id | VARCHAR(64) | PK | e.g. "monk-ki", "barbarian-rage", "dragonborn-breath" |
| display_name | VARCHAR(100) | NOT NULL | e.g. "Ki Points", "Rage" |
| source_type | VARCHAR(20) | NOT NULL | CLASS, RACE, FEAT, MONSTER, MAGIC_ITEM, BACKGROUND |
| max_uses_formula | VARCHAR(100) | | Expression for dynamic max, e.g. "monkLevel", "proficiencyBonus". NULL = use `max_uses` constant. |
| reset_on | VARCHAR(20) | NOT NULL | shortRest, longRest, turn, dawn, never |
| reset_amount | VARCHAR(100) | | Expression for partial recovery; NULL = full reset to maxUses |
| reset_check | VARCHAR(20) | | Probabilistic recharge: "1d6>=5", "1d6>=6" |
| spend_action_type | VARCHAR(30) | | ACTION, BONUS_ACTION, REACTION, FREE, REPLACE_ATTACK |
| description | TEXT | | Human-readable rules explanation |
| icon | VARCHAR(30) | | lucide-react icon name |
| metadata | JSONB | | Extra properties (hitDie faces, breath shape, etc.) |
| created_at | TIMESTAMPTZ | | |

### class_feature_pools

Links character classes and subclasses to the resource pool definitions they grant.

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | UUID | PK | |
| class_id | UUID | FK → character_classes, NOT NULL | |
| subclass_id | UUID | FK → subclasses, NULLABLE | NULL = base class feature |
| min_level | INTEGER | NOT NULL | Minimum class level to gain the pool |
| pool_id | VARCHAR(64) | FK → resource_pool_definitions, NOT NULL | |
| created_at | TIMESTAMPTZ | | |

### monster_pool_triggers

Defines conditions under which a monster automatically receives a resource pool when added to an encounter.

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | UUID | PK | |
| pool_id | VARCHAR(64) | FK → resource_pool_definitions, NOT NULL | |
| trigger_condition | JSONB | NOT NULL | Condition expression, e.g. `{"hasField": "legendary"}`, `{"hasRecharge": true}` |
| priority | INTEGER | DEFAULT 0 | Evaluation order when multiple triggers match |
| created_at | TIMESTAMPTZ | | |

The `trigger_condition` JSONB supports these expressions:
- `{"hasField": "legendary"}` — monster has the `legendary` field (legendary actions)
- `{"hasRecharge": true}` — monster has any action with a `recharge` property
- `{"hasFeature": "legendary_resistance"}` — monster has the legendary resistance trait

## Combat Tables (Milestone 5)

### combat_logs

Append-only log of every combat action in an encounter. One row per action.

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | UUID | PK | |
| encounter_id | UUID | FK → encounters, NOT NULL | |
| round_number | INTEGER | | Round when action occurred |
| actor_id | UUID | | Participant who performed the action (null for system actions) |
| actor_name | VARCHAR(200) | | Display name snapshot at time of action |
| target_id | UUID | | Participant affected |
| target_name | VARCHAR(200) | | Display name snapshot at time of action |
| action_type | VARCHAR(30) | NOT NULL | ATTACK, DAMAGE, HEAL, CONDITION_ADD, CONDITION_REMOVE, DEATH_SAVE, CONCENTRATION_CHECK, CONCENTRATION_LOST, TURN_ADVANCE, TURN_BACK, STABILIZE, KILL, REVIVE, SPELL_SLOT_USE, SPELL_SLOT_RESTORE, SPELL_CAST |
| description | TEXT | NOT NULL | Human-readable action description |
| roll_value | INTEGER | | Raw d20 roll (before modifiers) |
| roll_total | INTEGER | | Roll + modifiers |
| damage_dealt | INTEGER | | Actual damage applied |
| healing_done | INTEGER | | Actual healing applied |
| turn_participant_name | VARCHAR(200) | | Display name of the participant whose turn it was when the action occurred |
| created_at | TIMESTAMPTZ | | |

## Indexes

### JPA-Managed Indexes (`@Table(indexes)`)

| Table | Column(s) | Index Name | Notes |
|-------|-----------|------------|-------|
| player_characters | user_id | idx_pc_user_id | FK lookup for "my characters" |
| player_characters | campaign_id | idx_pc_campaign_id | FK lookup for campaign character lists |
| encounter_participants | encounter_id | idx_ep_encounter_id | FK lookup for loading encounter participants |
| encounter_participants | character_id | idx_ep_character_id | FK lookup for "is this character in combat" checks |
| combat_logs | encounter_id | idx_cl_encounter_id | FK lookup for combat log retrieval |

### SQL-Managed Indexes (`schema.sql`)

| Table | Column(s) | Index Name | Type | Notes |
|-------|-----------|------------|------|-------|
| spells | classes | idx_spells_classes_gin | GIN | Supports JSONB containment queries for filtering spells by class |

## Querying the Database Directly

```bash
# Connect to psql
docker exec -it tabletophelper-db psql -U tabletophelper -d tabletophelper

# List all tables
\dt

# Show all users
SELECT id, username, email, display_name, created_at FROM users;

# Show campaigns with DM name
SELECT c.name, c.invite_code, u.display_name AS dm
FROM campaigns c JOIN users u ON c.dm_user_id = u.id;

# Show characters with owner
SELECT pc.name, pc.race, pc.character_class, pc.level, u.display_name AS player
FROM player_characters pc JOIN users u ON pc.user_id = u.id;

# Show resource pool definitions (M24.5)
SELECT * FROM resource_pool_definitions;

# Show which pools a class gets at which level (M24.5)
SELECT c.name AS class, rpd.display_name, cfp.min_level
FROM class_feature_pools cfp
JOIN character_classes c ON c.id = cfp.class_id
JOIN resource_pool_definitions rpd ON rpd.pool_id = cfp.pool_id
ORDER BY c.name, cfp.min_level;
```
