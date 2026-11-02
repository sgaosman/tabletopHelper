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
| spell_slots | JSONB | | `{"1": {total, used}, …, "pact_1": {total, used}}` — pact slots are keyed `pact_N`. Converted to `{max, remaining}` when a character joins an encounter (D107). Since R1, a single Spellcasting class uses its own progression (D153); the recompute-spell-slots job corrects maximums stored before that. **Planned (R6, D168):** `{max, used}` everywhere. |
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
| race_id | UUID | FK → races, NOT NULL | Reference to seeded race. NOT NULL since V11 (D152). |
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

**Planned (R7, D169):** where the PHB uses level thresholds, pool sizes come from level tables instead of formulas, and a pool's reset type can change by level (e.g. Bardic Inspiration from long rest to short rest at Bard 5). Existing pools are resized at their next sync, and current uses are capped at the new maximum.

---

## Reference Tables (Milestone 3 — Seeded from 5e.tools)

These tables are populated automatically on startup by `DataSeeder` if empty. Data comes from the 2014 branch of 5e.tools.

Because most seeders skip a table that already has rows, data fixes don't reach existing databases. **Planned (R7, D169):** loaders update rows by name and source, never delete and reload, and re-run only when a data file's content hash changes (new `content_versions` table).

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
| action_templates | JSONB | Structured monster action data: actions, legendary and lair actions, spellcasting, innate spellcasting. Seeded by `MonsterActionSeeder` from `monster-action-definitions.json` (V9, D139). |
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

**Fixed in R1 (D169):** `FeatSeeder` used to delete and reload every feat on each startup, changing feat IDs. It now backfills effects in place, so IDs stay stable. The `relink-feat-ids` job re-points `featId` values that `player_characters.level_history` stored before the fix.

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
| monsters_in_lair | JSONB | | Array of monster IDs fighting in their lair (V9, D136). Written but never read by the backend; R5 completes it (D163). |
| version | BIGINT | | Optimistic locking (`@Version`, D034) |
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
| spell_slots_current | JSONB | | Copied from character on encounter join and converted to `{"1": {max, remaining}, …}` (D107). For monsters with innate spellcasting it currently holds an `{"innate": true, …}` object instead; R5 removes that (D165). **Planned (R5, D168):** `{max, used}`. |
| spell_attack_bonus | INTEGER | | Copied from character on encounter join |
| spell_save_dc | INTEGER | | Copied from character on encounter join |
| spellcasting_ability | VARCHAR(20) | | Copied from character on encounter join |
| spells_known | JSONB | | Copied from character on encounter join |
| **resource_pools_current** | **JSONB** | | **NEW (M24.5).** Array of ResourcePoolEntry objects. Copied from character sheet on join for PCs; generated from monster pool triggers for monsters. |
| is_visible_to_players | BOOLEAN | DEFAULT TRUE | |
| is_alive | BOOLEAN | DEFAULT TRUE | |
| is_current_turn | BOOLEAN | DEFAULT FALSE | **Planned (R5, D163):** dropped. Whose turn it is comes from `encounters.current_turn_index`. |
| controlled_by_user_id | UUID | FK → users, NULLABLE | For PLAYER, set to character owner |
| death_save_successes | INTEGER | DEFAULT 0 | |
| death_save_failures | INTEGER | DEFAULT 0 | |
| notes | TEXT | | DM-only notes |
| action_used | BOOLEAN | DEFAULT FALSE | Action economy — separate from pool system |
| bonus_action_used | BOOLEAN | DEFAULT FALSE | Action economy — separate from pool system |
| reaction_used | BOOLEAN | DEFAULT FALSE | Action economy — separate from pool system |
| last_lair_action_used | VARCHAR(200) | | Lair action used last round, to enforce the no-repeat rule (V9, D136) |
| version | BIGINT | | Optimistic locking (`@Version`, D034) |
| created_at | TIMESTAMPTZ | | |
| updated_at | TIMESTAMPTZ | | |

### encounter_participants resource_pools_current Format

Same `ResourcePoolEntry` JSONB array format as `player_characters.resource_pools`. During combat, `currentUses` is tracked independently from the character sheet. Per-turn resets (`resetOn == "turn"`) and recharge checks (`resetCheck`) are evaluated by `advanceTurn()`. On encounter COMPLETED, currentUses values are synced back to the character sheet for matching `poolId`s.

**Planned (R6, D166):** replaced by Write-back. When the DM applies results at completion, the *change* in uses during the encounter is applied to the sheet, along with HP, spell slots, hit dice and death, rather than copying final values over. This lets a character be in several encounters at once.

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

**Known issue (fixed in R5, D165):** the `hasFeature` check returns true for any monster whose legendary actions text contains "legendary", so monsters with legendary actions but no Legendary Resistance still get a Legendary Resistance pool. R5 matches exact feature names.

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
| action_type | VARCHAR(30) | NOT NULL, CHECK | ATTACK, DAMAGE, HEAL, CONDITION_ADD, CONDITION_REMOVE, DEATH_SAVE, CONCENTRATION_CHECK, CONCENTRATION_LOST, TURN_ADVANCE, TURN_BACK, STABILIZE, KILL, REVIVE, SPELL_SLOT_USE, SPELL_SLOT_RESTORE, SPELL_CAST, SPELL_EFFECT_REPEAT (V5), MONSTER_ACTION, LEGENDARY_ACTION, LAIR_ACTION, LEGENDARY_RESISTANCE_USED, RECHARGE_SUCCESS, RECHARGE_FAILURE (V9) |
| description | TEXT | NOT NULL | Human-readable action description |
| roll_value | INTEGER | | Raw d20 roll (before modifiers) |
| roll_total | INTEGER | | Roll + modifiers |
| damage_dealt | INTEGER | | Actual damage applied |
| healing_done | INTEGER | | Actual healing applied |
| turn_participant_name | VARCHAR(200) | | Display name of the participant whose turn it was when the action occurred |
| legendary_resistance_eligible | BOOLEAN | DEFAULT FALSE | Failed save by a creature that could use Legendary Resistance (V10, D145) |
| lr_target_id | UUID | | Participant who failed the save |
| lr_damage_dealt | INTEGER | | Damage from the failed save, refunded if Legendary Resistance is used. **Planned (R5, D163):** only the difference from the success outcome is refunded. |
| lr_conditions_applied | VARCHAR(500) | | Comma-separated conditions applied by the failed save, removed if Legendary Resistance is used |
| lr_resolved | BOOLEAN | DEFAULT FALSE | Set once Legendary Resistance is used or declined |
| created_at | TIMESTAMPTZ | | |

## Indexes

All indexes are created by Flyway migrations (D170). R2 removed the JPA `@Index` annotations, which Hibernate had only applied under `ddl-auto: update` (D080, D089).

### SQL-Managed Indexes (`schema.sql`)

> `schema.sql` is no longer used. This index is now created by migration V1.

| Table | Column(s) | Index Name | Type | Notes |
|-------|-----------|------------|------|-------|
| spells | classes | idx_spells_classes_gin | GIN | Supports JSONB containment queries for filtering spells by class |

### Flyway-Managed Indexes (what actually exists)

| Table | Column(s) | Index Name | Type | Created by |
|-------|-----------|------------|------|------------|
| monsters | lower(name) | idx_monsters_name_trgm | GIN (pg_trgm) | V0 — fuzzy monster search (D019) |
| spells | classes | idx_spells_classes_gin | GIN | V1 — class filtering on spells |
| player_characters | user_id | idx_pc_user_id | B-tree | V12 — "my characters" |
| player_characters | campaign_id | idx_pc_campaign_id | B-tree | V12 — campaign character lists |
| encounter_participants | encounter_id | idx_ep_encounter_id | B-tree | V12 — loading encounter participants |
| encounter_participants | character_id | idx_ep_character_id | B-tree | V12 — "is this character in combat" checks |
| combat_logs | encounter_id | idx_cl_encounter_id | B-tree | V12 — combat log retrieval |
| encounters | campaign_id | idx_enc_campaign_id | B-tree | V12 — a campaign's encounters |
| campaign_members | user_id | idx_cm_user_id | B-tree | V12 — a user's campaigns |

PostgreSQL also creates an index automatically for every primary key and UNIQUE constraint: `users.username`, `users.email`, `campaigns.invite_code`, `encounters.session_code`, `conditions.name` and `campaign_members(campaign_id, user_id)`. The last one also serves lookups by `campaign_id`.

These foreign keys stay unindexed because no query filters on them: `campaigns.dm_user_id`, `encounter_participants.monster_id`, the `race_id`, `class_id`, `subclass_id` and `background_id` columns of `player_characters`, `subclasses.character_class_id`, the three `class_feature_pools` keys and `monster_pool_triggers.pool_id`. `FlywayMigrationIT` fails if any other foreign key has no index, or if one of these gains an index without being taken off its allow-list.

## Flyway Migration History

| Version | File | Change |
|---------|------|--------|
| V0 | `V0__initial_schema.sql` | Full schema snapshot for fresh databases (D121), including the monster-name trigram index |
| V1 | `V1__gin_index_spells_classes.sql` | GIN index on `spells.classes` |
| V2 | `V2__spell_resolver_fields.sql` | `spells.effect_template`; participant `spell_attack_bonus`, `spell_save_dc`, `spellcasting_ability`, `spells_known` |
| V3 | `V3__add_spell_cast_action_type.sql` | Adds `SPELL_CAST` to `combat_logs.action_type` |
| V4 | `V4__add_concentration_slot_level.sql` | `encounter_participants.concentration_slot_level`; clears `spells.effect_template` to force a re-seed |
| V5 | `V5__add_spell_effect_repeat_action_type.sql` | Adds `SPELL_EFFECT_REPEAT` to `combat_logs.action_type` |
| V6 | `V6__add_active_spell_tracking.sql` | `encounter_participants.active_spell`, `active_spell_slot_level`; clears `spells.effect_template` to force a re-seed |
| V7 | — | Never created. Flyway accepts the gap. |
| V8 | `V8__resource_pool_system.sql` | `resource_pool_definitions`, `class_feature_pools`, `monster_pool_triggers`; `player_characters.resource_pools`; participant `resource_pools_current`, `action_used`, `bonus_action_used`, `reaction_used` (D125) |
| V9 | `V9__monster_action_templates.sql` | `monsters.action_templates`; monster, legendary, lair, legendary resistance and recharge values for `combat_logs.action_type`; `encounter_participants.last_lair_action_used`; `encounters.monsters_in_lair` (D136, D139) |
| V10 | `V10__legendary_resistance_metadata.sql` | Legendary Resistance undo columns on `combat_logs` (D145) |
| V11 | `V11__player_characters_race_id_not_null.sql` | `player_characters.race_id` NOT NULL (D152) |
| V12 | `V12__foreign_key_indexes.sql` | Indexes on the seven foreign keys used in lookups and joins, all `IF NOT EXISTS` (D170) |

V4 and V6 force a re-seed by clearing data. D169 replaces that pattern with hash-based re-seeding.

## Maintenance Jobs

One-off data corrections that run with the backend after reference data seeding, then exit. Each one prints a report and is a dry run unless `--maintenance.apply=true` is passed (D150).

| Job | What it changes | Decision |
|-----|-----------------|----------|
| `recompute-spell-slots` | Recomputes spell slot maximums in `player_characters.spell_slots`. Used counts are kept, capped at the new maximum. | D153 |
| `relink-feat-ids` | Re-points `featId` in `level_history` feat records at the current feat with the same name. | D169 |

Run locally from `backend/`:
`./gradlew bootRun --args="--maintenance.job=<name> --spring.main.web-application-type=none"`
Add `--maintenance.apply=true` to write the changes.

## Planned Schema Changes (Refactor Phase, D150–D172)

Approved 2026-11-01; D150 defines the phases. R1 and R2 are concrete. Later phases are outlines: their exact columns are decided in each phase's plan, and this section is updated then.

### R1 — Safety and hygiene

**Done (2026-11-01):** V11 is added, and both data changes are available as maintenance jobs (see [Maintenance Jobs](#maintenance-jobs)).

| Change | Type | Decision |
|--------|------|----------|
| `player_characters.race_id` becomes NOT NULL (next migration, expected V11). No production row can be null: the startup cleanup R1 removes would already have deleted it. | Schema | D152 |
| Recompute spell slot maximums in `player_characters.spell_slots` for single-class half and third casters. Used counts are kept and capped at the new maximum. | Data only | D153 |
| Re-link `featId` values in `level_history` ability score records by feat name. Feat IDs stop changing on restart. | Data only | D169 |

The two data changes run with the dry-run report and backup that D150 requires. The R1 plan decides how they are run.

### R2 — Test harness

| Change | Type | Decision |
|--------|------|----------|
| Foreign-key indexes, done in V12 — see [Flyway-Managed Indexes](#flyway-managed-indexes-what-actually-exists). JPA `@Index` annotations are removed. | Schema | D170 |

### R3 — Access and auth

| Change | Type | Decision |
|--------|------|----------|
| New `refresh_tokens` table (V13): `id`, `user_id` (FK → users, indexed), `family_id` (indexed), `token_hash` (SHA-256, unique), `issued_at`, `expires_at`, `replaced_by_id`, `revoked_at` | Schema | D156 |
| `campaigns.show_exact_monster_hp` BOOLEAN NOT NULL DEFAULT FALSE (V14) | Schema | D157 |

### R3–R7 — Outline

| Phase | Area | Expected change | Decision |
|-------|------|-----------------|----------|
| R5 | Campaigns | Campaign setting "allow player Manual Modifiers" | D164 |
| R5 | Encounter participants | `active_conditions` entries gain a Concentration Instance reference, a Condition End Rule and an optional Repeat Save. `concentration_spell` and `concentration_slot_level` are replaced by a Concentration Instance record. | D162 |
| R5 | Encounter participants | `is_current_turn` dropped (derived from `encounters.current_turn_index`) | D163 |
| R5 | Encounter participants | `spell_slots_current` normalised to `{max, used}`; innate uses move into `resource_pools_current` | D165, D168 |
| R5 | Combat log | Legendary Resistance metadata records enough to apply the success outcome. `action_type` gains new values as needed, e.g. repeat saves and DM overrides. | D161, D162, D163 |
| R6 | Player characters | `spell_slots` normalised to `{max, used}`. Every JSONB column gets a typed shape, with migrations that normalise existing rows. | D168 |
| R6 | Player characters | Storage for Pending Choices and DM Adjustments. `level_history` entries gain the hit die result and the ability score increase actually applied. `hp_max` becomes a derived value; R6 decides whether to keep a stored copy. | D158, D159, D167 |
| R6 | Encounters | Participants keep the values each character joined with, so Write-back can apply changes. Encounters record whether results were applied at completion. | D166 |
| R7 | Content | New `content_versions` table: data file name, content hash and load time. `resource_pool_definitions` gains level-table sizing and level-dependent reset types. | D169 |
| R7 | Player characters | Drop the deprecated `hit_dice_total`, `hit_dice_remaining`, `hit_dice_map`, `feat_resources` and `prepared_spells`, one release after nothing reads them | D150 |

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
