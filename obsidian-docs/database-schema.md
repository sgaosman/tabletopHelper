# Database Schema

PostgreSQL 16, accessed via Spring Data JPA with Hibernate 7 (7.4 since R8, D154), with the schema managed by Flyway (migrations V1–V29). All IDs are UUIDs. All entities have `created_at` and `updated_at` timestamps. JSON fields use PostgreSQL `jsonb` column type.

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

### refresh_tokens

Added in V13 (D156). One row per refresh token. The browser holds the random token in the `refresh_token` cookie; only its hash is stored. Every token issued from one login or registration shares a `family_id`. Each refresh replaces the token, and presenting a replaced token, or logging out, revokes the whole family (see [[auth-flow]]). The table has no `created_at`/`updated_at`; `issued_at` records creation. Expired and revoked rows are not cleaned up yet.

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | UUID | PK, DEFAULT gen_random_uuid() | |
| user_id | UUID | FK → users, NOT NULL | Indexed (`idx_rt_user_id`) |
| family_id | UUID | NOT NULL | Shared by every token from one login. Indexed (`idx_rt_family_id`) for revoking a family. |
| token_hash | VARCHAR(64) | UNIQUE, NOT NULL | SHA-256 of the token as lowercase hex. The token itself is never stored. |
| issued_at | TIMESTAMPTZ | NOT NULL | |
| expires_at | TIMESTAMPTZ | NOT NULL | 7 days after `issued_at` |
| replaced_by_id | UUID | | The token this one was exchanged for, set when it is used. No foreign key constraint. |
| revoked_at | TIMESTAMPTZ | | Set on every unrevoked token in the family at logout or on reuse |

### campaigns

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | UUID | PK | |
| name | VARCHAR(200) | NOT NULL | |
| description | TEXT | | |
| dm_user_id | UUID | FK → users, NOT NULL | |
| invite_code | VARCHAR(8) | UNIQUE, NOT NULL | Random alphanumeric, excludes ambiguous chars (0/O, 1/I/L) |
| is_active | BOOLEAN | DEFAULT TRUE | |
| show_exact_monster_hp | BOOLEAN | NOT NULL, DEFAULT FALSE | Campaign setting. Off: the Player View shows each monster's HP as a Health Status instead of numbers. Set by the DM through `PATCH /api/campaigns/{id}/settings` (V14, D157). |
| prompt_player_repeat_saves | BOOLEAN | NOT NULL, DEFAULT FALSE | Campaign setting. Off: the server rolls player characters' Repeat Saves, as it does monsters'. On: the condition waits for the player to roll (`repeatSave.awaitingRoll`) (V17, D162). |
| allow_player_manual_modifiers | BOOLEAN | NOT NULL, DEFAULT TRUE | Campaign setting. On: players may enter Manual Modifiers in combat (a spell attack bonus, save DC or forced critical), as the DM always may; each use is logged. Off: only the DM may (V18, D164). |
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
| strength - charisma | INTEGER | NOT NULL | Six ability scores. Since R6 workstream E they hold the scores without DM Adjustments: what the race, ASIs and feats give, including a feat the DM granted. An ASI raises them only as far as the Ability Score Maximum. The scores with the active STAT adjustments, clamped to 1–30, are worked out when read, and the rules and combat use those (D158). |
| hp_max | INTEGER | NOT NULL | |
| hp_current | INTEGER | NOT NULL | |
| hp_temp | INTEGER | DEFAULT 0 | |
| hit_dice_total (dropped by V29 on 2026-11-06) | VARCHAR(20) | | e.g. "8d10" — **DEPRECATED**, unmapped since V19 (R6, D168): the sheet builds the text from `hit_dice`. R7 drops it. |
| hit_dice_remaining (dropped by V29 on 2026-11-06) | VARCHAR(20) | | **DEPRECATED**, unmapped since V19 (R6, D168). R7 drops it. |
| armour_class | INTEGER | NOT NULL | The AC the character was created with, 10 + its DEX modifier then, because armour isn't modelled. Since R6 workstream E it holds the value without DM Adjustments: the AC the API returns, and an encounter copies, is this plus the active AC adjustments, worked out when read (D158). |
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
| spell_slots | JSONB | | `{"1": {total, used}, …, "pact_1": {total, used}}` — pact slots are keyed `pact_N`. Copied as `{max, used}` when a character joins an encounter (V15, D168). Since R1, a single Spellcasting class uses its own progression (D153); the recompute-spell-slots job corrects maximums stored before that. Since V19 (R6, D168) the sheet stores `{max, used}` as well. |
| spell_save_dc | INTEGER | | |
| spell_attack_bonus | INTEGER | | |
| spellcasting_ability | VARCHAR(20) | | |
| equipment | JSONB | | Inventory entries `{name, quantity, description, itemId, equipped, attuned}`; V19 turned stored names into entries (R6, D168). `itemId` is optional; since workstream D it links an entry to an item, must name an item that exists (400 otherwise, at creation too), and weapon matching tries it before the name (D164). |
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
| prepared_spells (dropped by V29 on 2026-11-06) | JSONB | DEPRECATED — unmapped since V19 | Legacy column, superseded by `spells_known[].prepared`. R7 drops it. |
| attuned_items (dropped by V29 on 2026-11-06) | JSONB | DEPRECATED — unmapped since V19 | Replaced by `equipment[].attuned` (R6, D168). R7 drops it. |
| equipped_items (dropped by V29 on 2026-11-06) | JSONB | DEPRECATED — unmapped since V19 | Replaced by `equipment[].equipped` (R6, D168). R7 drops it. |
| hit_dice_map (dropped by V29 on 2026-11-06) | JSONB | **DEPRECATED** — unmapped since V19 | Per-class hit dice: {className: {total, remaining, faces}}, copied into `hit_dice` by V19. No resource pool holds hit dice. R7 drops it. |
| hit_dice | JSONB | | Per-class hit dice `{className: {dieSize, total, remaining}}`, filled from `hit_dice_map` by V19 (R6, D158, D168) |
| level_history | JSONB | | Array of per-level entries for deterministic rollback. Since R6 workstream C (D167) each entry records its `hitDieResult`, the Hit Die Result the HP maximum is worked out from (V20 filled it in for older entries); `hpGained`, what the level added to the maximum, is a record only. An entry also records an ASI's increases as applied (`choices.asi.applied`), the skills given Expertise (`choices.expertise`), Primal Champion's increases (`abilityIncreases`) and the armour, weapon and tool proficiencies a multiclass level added (`proficienciesGained`), so levelling down reverses exactly those. Since R6 workstream F (D159) `choices` also records the Pending Choices made for the level: `subclass` (`{classId, subclassId, subclassName}`, on the class's subclass level), `fightingStyle`, `cantrips`, `spells` (`{learned, replaced, replacement}`, where a known caster's swap gives the spell replaced, as the sheet had it, and its replacement) and `feats` (a race's or background's feat on the 1st level, `{featName, featId, appliedEffects}`). `featuresGained` also gains a Fighting Style's feature, and the features a subclass chosen later brings at each level. Levelling down reverses these too, a swapped spell coming back. A subclass chosen before F isn't recorded, so levelling down past it doesn't take it away. Since the R6 review fixes a feat's `appliedEffects` can hold `spellcastingAbility`: the race and feat spellcasting ability the feat set, because the character had none. Reversing the feat clears it (D159). Since the follow-up, every spell-granting feat's `appliedEffects` holds the ability it chose, and `spellcastingAbilitySet` says whether it set the character's; a record without the flag set it. Since R7 workstream H a level's `choices.setsSpellcastingAbility` marks the level that stored a class's spellcasting ability, at creation or when an Eldritch Knight or Arcane Trickster subclass is chosen; levelling down past it works the ability out again (D159). |
| feat_resources (dropped by V29 on 2026-11-06) | JSONB | **DEPRECATED** — superseded by `resource_pools`, unmapped since R6 workstream D | Array of feat resource entries. Nothing reads or writes it since R6 workstream D (D158): a feat's limited uses, such as Lucky's, are a `feat:<feat>` pool in `resource_pools`. No local character had data in it. R7 drops it. |
| **resource_pools** | **JSONB** | | **NEW (M24.5).** Array of ResourcePoolEntry objects. See the [Resource Pool reference tables](#resource-pool-tables-m245) section for schema. It replaced `feat_resources`: since R6 workstream D a feat's limited uses are a `feat:<feat>` pool (below). No pool holds hit dice, which have their own `hit_dice` column (D168). Since R6 workstream C it also holds an `innate:<spell>` pool for each known spell with uses of its own, such as a race or feat spell, which resets on a long rest (D165). The pools are worked out again with the Derived Stats, and each keeps the uses already spent (D167). Since R6 workstream D it also holds a `feat:<feat>` pool, source type `FEAT`, for each feat with limited uses that the level history records, such as Lucky's `feat:lucky`: spending a use takes no action, and the pool resets on the rest the feat names (D158). Since R6 workstream F a feat a race or background gives at creation, such as a Variant Human's Lucky, gets its pool too, because the 1st level records it once its FEAT Pending Choice is made (D159). |
| is_active | BOOLEAN | DEFAULT TRUE | Soft delete |
| version | BIGINT | NOT NULL, DEFAULT 0 | Optimistic locking (`@Version`, V21, D158), the backstop behind the row lock every character command takes: a write from a copy that a command has changed since answers 409 |
| dm_adjustments | JSONB | NOT NULL, DEFAULT `'[]'` | The DM Adjustments, oldest first (V22, R6 workstream E, D158), each a `character.state.DmAdjustment` `{id, kind, target, amount, defence, featId, appliedEffects, reason, addedBy, addedAt, removedBy, removedAt}`; a field its kind doesn't use is left out. `kind` is `STAT`, `DEFENCE` or `FEAT`. A STAT's `target` is `ARMOUR_CLASS`, `HP_MAX`, `SPEED`, `INITIATIVE`, `SPELL_SAVE_DC`, `SPELL_ATTACK_BONUS` or an ability, `STR` to `CHA`, and its `amount` is a whole number within ±100, never 0. A DEFENCE's `target` is one of the 13 damage types in lower case, and its `defence` is `RESISTANCE`, `IMMUNITY` or `VULNERABILITY` (D161). A FEAT's `target` is the feat's name, with its `featId` and the `appliedEffects` that removing it reverses. `reason` is required, at most 200 characters, and the player sees it. `addedBy` and `removedBy` are the DM's user ID. A removed entry gains `removedBy` and `removedAt` and stays as the record; only active ones count. The stored `hp_max`, `initiative_bonus`, `speed`, `spell_save_dc` and `spell_attack_bonus` include the active STAT adjustments, as `CharacterStats` writes them; the ability scores, `armour_class` and the damage defence lists don't. |
| pending_choices | JSONB | NOT NULL, DEFAULT `'[]'` | The Pending Choices: the choices the character's levels owe that its player hasn't made yet, oldest level first, a subclass first within a level (V23, R6 workstream F, D159). Each is a `character.state.PendingChoice` `{id, type, source, characterLevel, classId, className, classLevel, count, options, maxSpellLevel, swap}`; a field its type doesn't use is left out. `type` is `SUBCLASS`, `FIGHTING_STYLE`, `ASI` (an ASI or a feat in its place), `FEAT` (a race's or background's feat at creation), `EXPERTISE`, `CANTRIPS` or `SPELLS`. `source` is `LEVEL_UP`, `CREATION`, `RACE` or `BACKGROUND`, and the `backfill-pending-choices` job's are `LEVEL_UP`. `characterLevel` is the level that owes the choice, whose `level_history` entry records the answer: 1 for a race's or background's feat, which has no class. `count` is how many skills, cantrips or spells to choose, else 1. `options` is the fixed list it is chosen from, where it has one: the class's subclasses or Fighting Styles, or the feats a background lists. `maxSpellLevel` is 0 for cantrips and, for spells, the highest level the class could learn at that class level. `swap` is true when a known caster may also replace one spell it knows. A Wizard's `SPELLS` choice is the spells it copies into its spellbook for free. Making a choice records the answer and takes the choice off the list, and levelling down drops the last level's. |
| is_dead | BOOLEAN | NOT NULL, DEFAULT FALSE | A character who died in an encounter whose results the DM applied (V24, R6 workstream G, D166). It stays at 0 hit points, and its rests, damage, healing and temporary hit points, level-up, level-down and joining an encounter answer 409, until the DM of its campaign revives it at 1 hit point, which clears the flag. Existing characters start with it false. |
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

These tables are populated automatically on startup by the `content` module's loaders (R7, D169), each re-run only when its files' hash changes. Data comes from the 2014 branch of 5e.tools and the project's own `data/content` files.

Because most seeders skip a table that already has rows, data fixes don't reach existing databases. **Planned (R7, D169):** loaders update rows by name and source, never delete and reload, and re-run only when a data file's content hash changes (new `content_versions` table).

**Done (R7 workstream B, D169):** the `content` module's loaders replace the seeders. Each finds a row by its natural key and updates it in place, keeping its ID. It inserts new rows and deletes none. A loader runs again only when the hash of its files and code version changes, or a loader it depends on ran. V26 makes the natural keys unique: (name, source) for races, classes, backgrounds, spells, feats, items, monsters and optional features, (class, name, source) for subclasses, and (class, subclass, pool) for `class_feature_pools`. A missing source or subclass counts as a value (`NULLS NOT DISTINCT`). `content.mode` (`apply`, `preview` or `off`) controls loading at startup.

### content_versions

What each content loader last loaded (V25, D169). One row per loader.

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| loader | VARCHAR(64) | PK | The loader's name, such as `spells` or `resource-pools` |
| content_hash | CHAR(64) | NOT NULL | SHA-256 of the loader's files and code version, as hex |
| loader_version | INT | NOT NULL | The loader's code version, raised when its code changes what it writes |
| row_count | INT | | The rows the data gave |
| loaded_at | TIMESTAMPTZ | NOT NULL | When it last loaded |

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
| action_templates | JSONB | Structured monster action data: actions, legendary and lair actions, spellcasting, innate spellcasting. Seeded by `MonsterActionSeeder` from `monster-action-definitions.json` (V9, D139). Since R7 workstream F it maps to the typed `monster.MonsterTemplates`, keeping the whole stored JSON; a condition given on a hit may carry an `escapeDC`, and an action a `dmNote` for the DM (D165, D168). |
| source | VARCHAR | e.g. "MM", "VGM" |
| is_homebrew | BOOLEAN | |
| created_at | TIMESTAMPTZ | |

**Count:** 2,684 monsters

**`action_templates` keys the encounter reads (R5, D168).** `MonsterTemplates` reads the column into records and ignores every other key. Since the R5 follow-ups it also reads a hit's saving throws: `saveDC` and `saveAbility` on condition effects and on attack actions, and `secondarySaveDC` and `secondarySaveAbility` on attack actions (D165). The column itself stays JSON until R7 gives it a record type. 2,357 monsters have templates, each a JSON object.
- Top level: `actions`, `legendaryActions`, `lairActions`, `bonusActions`, `reactions`, `traits`, `legendaryActionCount`, `legendaryResistanceCount` and `spellcasting`.
- Each action, legendary action, lair action and trait: `name`, `description`, `automatable`, `deliveryMethod`, `attackType`, `attackBonus`, `saveDC`, `saveAbility`, `halfOnSave`, `targetCount`, `effects`, `recharge`, `cost`, `isMultiattack`, `multiattackComponents`, `multiattackDescription`, `_multiattackNote`, `isEyeRays`, `rayCount` and `rays`.
- Each effect: `effectType`, `damageDice`, `damageType`, `condition`, `durationRounds`, `saveToEndEachTurn`, `saveToEndDC` and `saveToEndAbility`. Each ray: `name`, `saveDC`, `saveAbility`, `halfOnSave` and `effects`. The `spellcasting` block: `ability`, `attackBonus`, `saveDC`, `slots`, `spellsByLevel`, `dailySpells`, `atWillSpells` and `innateSpells`.
- Each key has one JSON type across the 2,357 templates (or null), with two exceptions. A legendary action's `cost` is a number (900 times) or the text `1-3` (twice, on the Illithilich and the Drow Matron Mother); the records read it as text, and `1-3` costs 1. `innateSpells` is a flag on 750 monsters and an object of daily and at-will lists on 18, so it is read as raw JSON.
- `flatDamage` is a flag (always `true`, 23 effects) on an effect whose `damageDice` is a flat amount such as `1`. It adds nothing, because `damageDice` already holds the amount, so it isn't read.
- `hasUnresolvedComponents` (`true` on 90 multiattacks) is ignored. The server works out for itself which multiattacks it can't read, from `multiattackComponents` and `_multiattackNote`, so the flag is redundant, and it is wrong for Moloch (MTF), whose three components all resolve. In total 124 of the 1,478 multiattacks can't be read.
- Data that doesn't fit the records stops the command with a 409 naming the monster's templates, and the cause is logged.

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
| effect_template | JSONB | `@JsonRawValue` — structured spell effect definition. Since R7 workstream F it maps to the typed `reference.SpellTemplate`, keeping the whole stored JSON, and `@JsonRawValue` is gone (D168) |
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

**Weapons for weapon attacks (R5, D164).** `ItemRepository.findMundaneWeapons()` returns the rows a character's equipment entries are matched against: `subtype` (the weapon category) is `simple` or `martial`, `damage_dice` is set, and `rarity` is NULL or `unknown`. 5etools' `none` is stored as NULL by `ItemSeeder`, and `unknown`, which 5etools gives some adventure weapons, is kept as it is. That is 55 rows with distinct names, 19 simple and 36 martial: the 54 base weapons less the Net, which deals no damage, plus the Iron Ball and the Oversized Longbow, two mundane weapons from adventures. The other rows with a weapon category are the Net and 173 magic weapons, which have a rarity, so none of them is in the set.
- `type` is `Ranged Weapon` for a ranged weapon. `properties.tags` carries the 5etools property codes the rules read: `F` finesse, `H` heavy and `AF` firearm ammunition. A code may have a `|source` suffix.
- Since R6 workstream D a `player_characters.equipment` entry's optional `itemId` links it to an item. An entry linked to one of these weapons is that weapon, whatever it is called; any other entry is matched by name, in any case and singular or plural (D164).

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
| effects | JSONB | What the race changes in the rules, from `race-effects.json` (V28, R7, D167): `{"hpPerLevel": 1}` for Hill Dwarf. NULL for most races. Not sent by the API |
| created_at | TIMESTAMPTZ | |

**Count:** 226 races

### character_classes

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| name | VARCHAR(100) | NOT NULL |
| source | VARCHAR(100) | |
| hit_dice | INTEGER | e.g. 8, 10, 12 |
| primary_ability | VARCHAR(50) | The abilities of the class's multiclassing prerequisite, such as "STR and CHA" for a Paladin and "STR or DEX" for a Fighter. Classes seeded before R6's click-through fixes held "OR" for the Fighter; the class seeder corrects it at startup (D169) |
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
| effects | JSONB | What the subclass changes in the rules, from `subclass-effects.json` (V28, R7, D167): `{"hpPerClassLevel": 1}` for the Draconic Bloodline. NULL for most. Not sent by the API |
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
| current_turn_participant_id | UUID | FK → encounter_participants ON DELETE SET NULL, NULLABLE | Whose turn it is (V17, D163). Replaced `current_turn_index` and `encounter_participants.is_current_turn`: the order is computed from initiative, DEX, the DM's tie order and the Lair's turn, so a stored position drifts. The API still returns `currentTurnIndex`, computed for each view. |
| is_lair_turn | BOOLEAN | NOT NULL, DEFAULT FALSE | The Lair's turn is current (V17, D163). CHECK: never set together with `current_turn_participant_id`. |
| round_number | INTEGER | DEFAULT 1 | |
| session_code | VARCHAR(8) | UNIQUE, NULLABLE | Generated on start, same charset as invite codes |
| monsters_in_lair | JSONB | | Array of monster IDs fighting in their lair (V9, D136). While any of them is alive, the turn order includes the Lair's turn at initiative 20, losing ties. The DM sets it with the In lair toggle (R5, D163). |
| results_applied | BOOLEAN | NULLABLE | The DM's choice on completing the encounter (V24, R6 workstream G, D166): `true` when its results were written back to the character sheets, `false` when the DM declined. NULL until it completes, and for encounters completed before R6. |
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
| sort_order | INTEGER | DEFAULT 0 | The DM's order among ties, meaning equal initiative and DEX. The turn order is initiative, then DEX, then this (D163). |
| hp_max | INTEGER | NOT NULL | |
| hp_current | INTEGER | NOT NULL | |
| hp_temp | INTEGER | DEFAULT 0 | Since R6 workstream G a PC joins with its sheet's temporary hit points; before, they started at 0 (D166) |
| armour_class | INTEGER | NOT NULL | |
| active_conditions | JSONB | | Array of condition entries `{id, name, duration, appliedRound, sourceSpellName, sourceParticipantId, sourceRequiresConcentration, concentrationInstanceId, endRule, repeatSave}` (V15, V16, D162). `endRule` is `{type, participantId}`, with type AFTER_ROUNDS, START_OF_TURN, END_OF_TURN, CONCENTRATION, REPEAT_SAVE, HP_REGAINED or MANUAL; `participantId` names whose turn a turn-based rule counts. `repeatSave` is `{ability, dc, awaitingRoll}`. |
| concentration | JSONB | | The Concentration Instance `{id, spell, slotLevel, startedRound, durationRounds}`, or NULL (V16, D162). Replaced `concentration_spell` and `concentration_slot_level`. Conditions it created carry its `id`. |
| active_spell | VARCHAR(200) | | Non-concentration persistent spell |
| active_spell_slot_level | INTEGER | | Slot level used to cast the active spell |
| spell_slots_current | JSONB | | Copied from character on encounter join as `{"1": {max, used}, …}`, with pact slots keyed `pact_N` (V15, D168). V15 removed the innate object that monsters with innate spellcasting held; innate uses are resource pools (D165). |
| spell_attack_bonus | INTEGER | | Copied from character on encounter join |
| spell_save_dc | INTEGER | | Copied from character on encounter join |
| spellcasting_ability | VARCHAR(20) | | Copied from character on encounter join |
| spells_known | JSONB | | Copied from character on encounter join |
| **resource_pools_current** | **JSONB** | | **NEW (M24.5).** Array of ResourcePoolEntry objects. Copied from character sheet on join for PCs. For monsters, built by `MonsterPools` from the pool triggers, recharge actions and innate spells, whose uses are pools named `innate:<spell>` (D165). Since R6 workstream C a PC's innate spell uses are sheet pools and join with it; one that joined without them starts them full on its first cast. |
| joined_with | JSONB | | What a player character joined with (V24, R6 workstream G, D166): a `character.state.CombatTotals` `{hpCurrent, hpTemp, slotsUsed, poolsCurrentUses}`, that is its hit points, temporary hit points, each slot's used count keyed as `spell_slots_current` is, and each pool's current uses keyed by pool ID. A participant takes it from its sheet when added and again when the encounter starts, and Write-back applies the change from it to the totals the participant ends with. V24 filled it from the sheets as they stood for the player participants of Active and Paused encounters, hit points kept between 0 and the maximum. NULL for monsters, and for participants of encounters completed before R6. |
| is_visible_to_players | BOOLEAN | DEFAULT TRUE | |
| life_state | VARCHAR(10) | NOT NULL, CHECK | ALIVE, DYING, STABLE or DEAD (V16, D161). Replaced `is_alive`, because a Stable PC's death saves are reset and can't otherwise be told from a Dying PC's. |
| controlled_by_user_id | UUID | FK → users, NULLABLE | For PLAYER, set to character owner |
| death_save_successes | INTEGER | DEFAULT 0 | |
| death_save_failures | INTEGER | DEFAULT 0 | |
| death_save_due | BOOLEAN | NOT NULL, DEFAULT FALSE | Set when a Dying PC's turn starts, until it rolls the death save or stops dying (V17, D163) |
| notes | TEXT | | DM-only notes |
| action_used | BOOLEAN | DEFAULT FALSE | Action economy — separate from pool system. Dropped by V30 (M13, D174) |
| bonus_action_used | BOOLEAN | DEFAULT FALSE | Action economy — separate from pool system. Dropped by V30 (M13, D174) |
| reaction_used | BOOLEAN | DEFAULT FALSE | Action economy — separate from pool system. Dropped by V30 (M13, D174) |
| turn_economy | JSONB | NOT NULL, DEFAULT the fresh record | The creature's budget this turn, an `encounter.state.TurnEconomy`: `actions`, `actionsUsed`, `attackActionTaken`, `attacksLeft`, `bonusActionUsed`, `reactionUsed`, `objectInteractionUsed`, `bonusActionSpellCast`, `otherSpellCast`, `dashed`, `disengaged`. Reset at the start of the creature's own turn (V30, M13, D174) |
| last_lair_action_used | VARCHAR(200) | | Lair action used last round, to enforce the no-repeat rule (V9, D136) |
| version | BIGINT | | Optimistic locking (`@Version`, D034) |
| created_at | TIMESTAMPTZ | | |
| updated_at | TIMESTAMPTZ | | |

### encounter_participants resource_pools_current Format

Same `ResourcePoolEntry` JSONB array format as `player_characters.resource_pools`. During combat, `currentUses` is tracked independently from the character sheet. Per-turn resets (`resetOn == "turn"`) and recharge checks (`resetCheck`) are evaluated at the start of the creature's turn by `TurnProcessor` (R5, D163). Since R6 workstream G nothing is synced back at COMPLETED: Write-back, below, applies the change in uses instead, after locking the character rows in ID order (D166).

**Done (R6 workstream G, D166):** replaced by Write-back. When the DM applies results at completion, or removes a player character from an Active encounter, the *change* in uses since the participant joined (`joined_with`) is applied to the sheet's pool with the same `poolId`, kept between 0 and its maximum, along with HP, spell slots and death, rather than copying final values over. This lets a character be in several encounters at once. If the DM declines, no pool changes, and monsters' pools are never written back.

## Resource Pool Tables (M24.5)

Seeded reference tables populated on startup by `ResourcePoolSeeder`. These define what resource pools exist and which class features / monster abilities grant them.

Since R7 workstream B, `ResourcePoolLoader` loads them from `data/content/resource-pools.json`, with the values the seeder held (D169).

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
| level_table | JSONB | | What changes with a class's level (V27, R7, D169): `{levelOf, rows: [{level, maxUses, resetOn, die, unlimited}]}`. `levelOf` names the class whose level picks the row, and a row applies from its level until the next one; a field it leaves out keeps the definition's own. NULL for a pool that never changes. |
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
- `{"hasRecharge": true}` — monster has any action with a `recharge` property. No trigger uses it since V18: each recharge action gets its own pool.
- `{"hasFeature": "Legendary Resistance"}` — monster has a trait of that name, ignoring a bracketed note such as "(3/Day)" (V18)

**Fixed (2026-11-02, V18, D165):** the Legendary Resistance trigger was `{"hasField": "legendary"}`, so every monster with legendary actions got the pool. V18 matches the trait instead: 336 monsters get the pool rather than 386, and the 50 that lose it, such as the Aboleth, have legendary actions but no Legendary Resistance. V18 also deletes the generic breath-weapon trigger, which duplicated the per-action recharge pools and matched no monster. Monsters already in an encounter keep the pools they joined with.

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
| action_type | VARCHAR(30) | NOT NULL, CHECK | ATTACK, DAMAGE, HEAL, CONDITION_ADD, CONDITION_REMOVE, DEATH_SAVE, CONCENTRATION_CHECK, CONCENTRATION_LOST, TURN_ADVANCE, TURN_BACK, STABILIZE, KILL, REVIVE, SPELL_SLOT_USE, SPELL_SLOT_RESTORE, SPELL_CAST, SPELL_EFFECT_REPEAT (V5), MONSTER_ACTION, LEGENDARY_ACTION, LAIR_ACTION, LEGENDARY_RESISTANCE_USED, RECHARGE_SUCCESS, RECHARGE_FAILURE (V9), HP_SET, REPEAT_SAVE (V16) |
| description | TEXT | NOT NULL | Human-readable action description |
| roll_value | INTEGER | | Raw d20 roll (before modifiers) |
| roll_total | INTEGER | | Roll + modifiers |
| damage_dealt | INTEGER | | Actual damage applied |
| healing_done | INTEGER | | Actual healing applied |
| turn_participant_name | VARCHAR(200) | | Display name of the participant whose turn it was when the action occurred |
| legendary_resistance_eligible | BOOLEAN | DEFAULT FALSE | Failed save by a creature that could use Legendary Resistance (V10, D145) |
| lr_target_id | UUID | | Participant who failed the save |
| lr_damage_dealt | INTEGER | | Damage from the failed save, refunded if Legendary Resistance is used. Since V16, only the difference from `lr_success_damage` is refunded (D163). |
| lr_conditions_applied | VARCHAR(500) | | Comma-separated conditions applied by the failed save, removed if Legendary Resistance is used. Entries since V16 use `lr_condition_ids`. |
| lr_resolved | BOOLEAN | DEFAULT FALSE | Set once Legendary Resistance is used or declined |
| lr_success_damage | INTEGER | | Damage the target would have taken on a successful save (V16, D163) |
| lr_condition_ids | JSONB | | IDs of the condition entries the failed save applied, removed if Legendary Resistance is used (V16, D163). Entries logged before V16 have neither column, so Legendary Resistance can't be used on them. |
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
| refresh_tokens | user_id | idx_rt_user_id | B-tree | V13 — keeps the foreign key indexed (D156) |
| refresh_tokens | family_id | idx_rt_family_id | B-tree | V13 — revoking a token family on logout or reuse (D156) |

PostgreSQL also creates an index automatically for every primary key and UNIQUE constraint: `users.username`, `users.email`, `campaigns.invite_code`, `encounters.session_code`, `conditions.name`, `refresh_tokens.token_hash` and `campaign_members(campaign_id, user_id)`. The last one also serves lookups by `campaign_id`.

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
| V13 | `V13__refresh_tokens.sql` | `refresh_tokens` table with `idx_rt_user_id` and `idx_rt_family_id` (D156) |
| V14 | `V14__campaign_show_exact_monster_hp.sql` | `campaigns.show_exact_monster_hp` BOOLEAN NOT NULL DEFAULT FALSE (D157) |
| V15 | `V15__typed_encounter_json.sql` | Participant spell slots become `{max, used}`, with pact slots keyed `pact_N`; the innate slot object becomes NULL; conditions stored as strings become objects; values of the wrong JSON type become NULL (D168) |
| V16 | `V16__combat_pipeline_state.sql` | `concentration` replaces the two concentration columns; `life_state` replaces `is_alive`; condition entries gain `id` and an end rule, and Dying and Stable PCs gain unconscious and prone; `combat_logs.lr_success_damage` and `lr_condition_ids`; `HP_SET` and `REPEAT_SAVE` action types (D160–D163) |
| V17 | `V17__turn_order.sql` | `encounters.current_turn_participant_id` and `is_lair_turn` replace `current_turn_index` and `encounter_participants.is_current_turn`; `encounter_participants.death_save_due`; `campaigns.prompt_player_repeat_saves` (D157, D162, D163) |
| V18 | `V18__manual_modifiers_and_monster_triggers.sql` | `campaigns.allow_player_manual_modifiers` BOOLEAN NOT NULL DEFAULT TRUE; the Legendary Resistance trigger becomes `{"hasFeature": "Legendary Resistance"}`; deletes the breath-weapon trigger (D164, D165) |
| V19 | `V19__typed_character_json.sql` | Character JSON normalised for typed records: slots become `{max, used}`, a new `hit_dice` column is filled from `hit_dice_map`, equipment names become entries, and values of the wrong JSON type become NULL (D168) |
| V20 | `V20__hit_die_results.sql` | Each `level_history` entry gains `hitDieResult`, with the values the old `calculateHpGain` used: the class's Hit Die at character level 1, and half the die plus 1 after that. The die comes from the entry's `classId`, else the character's Hit Dice of that class, else d8. Entries that already have a result are left alone, and the file has no DO blocks (D167) |
| V21 | `V21__character_version.sql` | `player_characters.version` BIGINT NOT NULL DEFAULT 0, the `@Version` behind the row lock every character command takes; existing characters start at 0, and the file has no DO blocks (D158) |
| V22 | `V22__dm_adjustments.sql` | `player_characters.dm_adjustments` JSONB NOT NULL DEFAULT `'[]'`, the DM Adjustments as `character.state.DmAdjustment` entries, oldest first; existing characters start with none, and the file has no DO blocks (D158, D161) |
| V23 | `V23__pending_choices.sql` | `player_characters.pending_choices` JSONB NOT NULL DEFAULT `'[]'`, the Pending Choices as `character.state.PendingChoice` entries, oldest level first; existing characters start with none, the `backfill-pending-choices` job adds what their levels missed, and the file has no DO blocks (D159) |
| V24 | `V24__write_back.sql` | `encounter_participants.joined_with` JSONB, a `character.state.CombatTotals`, filled from their sheets for the player participants of Active and Paused encounters: hit points kept between 0 and the maximum, temporary hit points at least 0, each slot's used count between 0 and its maximum, and the first of two pools with one ID. `encounters.results_applied` BOOLEAN, NULL until an encounter completes. `player_characters.is_dead` BOOLEAN NOT NULL DEFAULT FALSE. The file has no DO blocks (D166, D168) |
| V25 | `V25__content_versions.sql` | The `content_versions` table, one row per content loader (D169) |
| V26 | `V26__natural_key_indexes.sql` | Deletes the 14 duplicate Fighting Style copies in `optional_features`, keeping the oldest of each, after pointing any copy's ID in `level_history` and `dm_adjustments` at it. Then adds the unique natural-key indexes. The file has no DO blocks (D169) |
| V27 | `V27__pool_level_tables.sql` | `resource_pool_definitions.level_table` JSONB, NULL until the resource-pools loader fills it (D169) |
| V28 | `V28__race_and_subclass_effects.sql` | `races.effects` and `subclasses.effects` JSONB, NULL until the race and class loaders fill them (D167) |
| V29 | `V29__drop_deprecated_character_columns.sql` | Drops `player_characters.hit_dice_total`, `hit_dice_remaining`, `hit_dice_map`, `feat_resources`, `prepared_spells`, `attuned_items` and `equipped_items`, which nothing reads; `hit_dice` holds the hit dice. Destructive: archive the values first (D168) |
| V30 | `V30__turn_economy.sql` | `encounter_participants.turn_economy` JSONB NOT NULL with the fresh budget, filled from the three action flags, which are then dropped; the combat log types gain POOL_USE, STANDARD_ACTION, OBJECT_INTERACTION, TURN_ECONOMY_SET and REACTION_TAKEN. No DO blocks (D174) |

V4 and V6 force a re-seed by clearing data. D169 replaces that pattern with hash-based re-seeding.

## Maintenance Jobs

One-off data corrections that run with the backend after reference data seeding, then exit. Each one prints a report and is a dry run unless `--maintenance.apply=true` is passed (D150).

| Job | What it changes | Decision |
|-----|-----------------|----------|
| `recompute-spell-slots` | **Retired in R8 workstream I**; `recompute-characters` does this.  Recomputes spell slot maximums in `player_characters.spell_slots`. Used counts are kept, capped at the new maximum. | D153 |
| `relink-feat-ids` | **Retired in R8 workstream I**; feat IDs are stable since V26.  Re-points `featId` in `level_history` feat records at the current feat with the same name. | D169 |
| `preview-migration` | Nothing. Runs migration `--maintenance.version=N` inside a transaction that is always rolled back, prints every changed row before and after, and checks that each converted row loads. Pass `--spring.flyway.target=N-1 --spring.jpa.hibernate.ddl-auto=none` too, so Flyway doesn't apply the migration first. It splits the file into statements, so migrations it previews avoid DO blocks. | D168 |
| `recompute-characters` | Works out every character's Derived Stats again with the R6 rules and writes the stored copies: the HP maximum from the recorded Hit Die Results (a higher maximum raises current HP by as much, and a lower one caps it), proficiency bonus, initiative, speed, slot and Hit Dice maximums, the main spell DC and attack bonus, and the resource pools, innate uses included. It reports each changed stat before and after. What a sheet already has beyond the rules, such as more cantrips than its class knows, is a warning and is left as it is; a sheet that can't be worked out is skipped with a warning. | D167 |
| `backfill-pending-choices` | Adds to `player_characters.pending_choices` the Pending Choices that existing characters' levels missed, each marked `LEVEL_UP`: an ASI at a level that gives one and records none, a subclass for a class at or past its subclass level without one, and Expertise at a level that gives it and records none, less the Expertise the sheet has that no level records, counted against those levels oldest first. Fighting Styles and known spells aren't backfilled. It reports each character that would change with each choice, such as "ASI at Fighter 6 (level 6)", and skips a character whose classes can't be found with a warning. A second run adds nothing, since the choices it added are pending. | D159 |
| `content-snapshot` | Nothing. Writes the 13 reference tables as sorted JSON Lines, by ID and by natural key, to `--maintenance.output=<dir>`. With `--maintenance.compare=<dir>` it reports the rows added, deleted and changed against that earlier snapshot, and the natural keys whose ID changed; `--maintenance.compare-by=id` or `key` reports only one view (default `both`). For example: `--maintenance.job=content-snapshot --maintenance.output=../snapshots/before`. | D169 |
| `preview-content` | Nothing. Runs the content loaders the next start would run, inside a transaction it always rolls back, and reports each reference row they would add or change. | D169 |
| `json-roundtrip` | Nothing. Reads every typed JSONB column, writes each value back with the app's mapper and compares the two as jsonb, reporting changed and unreadable values, null-only differences and templates that don't fit their records, and listing the raw columns it skips. It refuses `--maintenance.apply=true`. | D168, D172 |

Run locally from `backend/`:
`./gradlew bootRun --args="--maintenance.job=<name> --server.port=0"`

The app starts on a random free port and exits when the job is done. `--spring.main.web-application-type=none` no longer starts it, because the error handler reads the server settings (since R3).
Add `--maintenance.apply=true` to write the changes.

## Planned Schema Changes (Refactor Phase, D150–D172)

Approved 2026-11-01; D150 defines the phases. R1, R2 and R3 are concrete. Later phases are outlines: their exact columns are decided in each phase's plan, and this section is updated then.

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

**Done (2026-11-02):** V13 and V14 are added; see [refresh_tokens](#refresh_tokens) and [campaigns](#campaigns).

| Change | Type | Decision |
|--------|------|----------|
| New `refresh_tokens` table (V13): `id`, `user_id` (FK → users, indexed), `family_id` (indexed), `token_hash` (SHA-256, unique), `issued_at`, `expires_at`, `replaced_by_id`, `revoked_at` | Schema | D156 |
| `campaigns.show_exact_monster_hp` BOOLEAN NOT NULL DEFAULT FALSE (V14) | Schema | D157 |

### R5 — Combat pipeline

| Migration | Lands in | Change |
|---|---|---|
| `V15__typed_encounter_json.sql` | C | `encounter_participants.spell_slots_current` changes from `{remaining, max}` to `{max, used}`; the innate object becomes NULL; `active_conditions` stored as string arrays become objects |
| `V16__combat_pipeline_state.sql` | D | **Participants:** `concentration` JSONB replaces `concentration_spell` and `concentration_slot_level`; `life_state` VARCHAR(10) NOT NULL CHECK (ALIVE, DYING, STABLE, DEAD) replaces `is_alive`; condition entries gain `id` and a MANUAL `endRule`; Dying and Stable PCs gain prone and an unconscious condition that ends when they regain HP (end rule HP_REGAINED). **`combat_logs`:** adds `lr_success_damage` INTEGER and `lr_condition_ids` JSONB; `action_type` adds `HP_SET` and `REPEAT_SAVE` |
| `V17__turn_order.sql` | E | **Encounters:** adds `current_turn_participant_id` (FK ON DELETE SET NULL, indexed) and `is_lair_turn`, never both set, filled from `is_current_turn` or else the turn index; drops `current_turn_index`. **Participants:** drops `is_current_turn`; adds `death_save_due` BOOLEAN NOT NULL DEFAULT FALSE. **Campaigns:** adds `prompt_player_repeat_saves` BOOLEAN NOT NULL DEFAULT FALSE |
| `V18__manual_modifiers_and_monster_triggers.sql` | F | Adds `campaigns.allow_player_manual_modifiers` BOOLEAN NOT NULL DEFAULT TRUE; changes the Legendary Resistance trigger to `{"hasFeature": "Legendary Resistance"}`; deletes the breath-weapon trigger |

V16 backfills existing rows: concentration is built from the old columns and the encounter's current round; a downed monster becomes DEAD; a downed PC becomes DEAD with three failures, STABLE with three successes (both counts reset), and DYING otherwise. Each of V15 and V16 gets a backup and a `preview-migration` dry run first (D168).

V17 stores the current turn by participant rather than by position, because the order is computed from initiative, DEX, the DM's tie order and the Lair's turn, so a stored position drifts as participants come and go (D163).

**Done (2026-11-02):** V15, V16, V17 and V18 are applied to the local database, each after a backup in `tabletopHelper-backups/` and a `preview-migration` dry run. V15 converted 12 slot rows. V16 changed 246 of 261 rows, leaving 57 participants Alive, 1 Dead and 2 Dying. V17 changed 75 rows, and every encounter kept the same creature on its turn. V18 changed the two monster pool triggers. No row failed to load.

### R6 — Character authority

The plan was approved on 2026-11-03 (D150). Migrations V19–V24 land with workstreams B–G.

| Migration | Lands in | Change |
|---|---|---|
| `V19__typed_character_json.sql` | B | Slots become `{max, used}`; `hit_dice` is filled from `hit_dice_map`; equipment names become entries; values of the wrong JSON type become NULL |
| `V20__hit_die_results.sql` | C | Each `level_history` entry gains `hitDieResult`: the die size at character level 1, and half the die plus 1 after that |
| `V21__character_version.sql` | D | `player_characters.version`, for optimistic locking behind the row lock |
| `V22__dm_adjustments.sql` | E | `player_characters.dm_adjustments` JSONB, default `[]` |
| `V23__pending_choices.sql` | F | `player_characters.pending_choices` JSONB, default `[]` |
| `V24__write_back.sql` | G | `encounter_participants.joined_with`, `encounters.results_applied` and `player_characters.is_dead` |

Two maintenance jobs, both dry runs unless `--maintenance.apply=true` is passed: `recompute-characters` (C) derives every character's stats again, and `backfill-pending-choices` (F) adds the choices existing characters missed.

**Done (2026-11-03):** V19 (workstream B) is applied to the local database after a backup and a `preview-migration` dry run: 276 rows examined, all 13 characters changed (hit dice on 13, slots on 5), and every row loads.

**Done (2026-11-03, workstream C):** V20 and the `recompute-characters` job are applied to the local database, each after a backup and a dry run. The backups are `before-r6-v20-20261103T0917Z.sql`, `before-r6-v20-final-20261103T0919Z.sql` and `before-r6-recompute-20261103T0920Z.sql` in `tabletopHelper-backups/`. The `preview-migration` dry run of V20 examined 276 rows and found 13 characters to change, none failing to load. V20 was applied at 09:19 UTC: 97 level entries on 13 characters gained `hitDieResult`, and on every entry `hpGained` minus `hitDieResult` equals the character's current CON modifier, which confirms the backfill. The job's dry run examined 13 characters and found 6 to change, all soft-deleted test characters; `asdasd`, the only active one, is unchanged. No HP maximum, proficiency bonus, slot, main DC, speed or Hit Dice changes. Initiative rises by 1 for TestBM, TestEcho and both TestFighters, whose DEX increases never reached it. Seven pools are added on 3 characters: Magic Awareness and Bolstering Magic, 3/3 each, and innate pools for Faerie Fire, Enlarge/Reduce and Absorb Elements, 1/1 each, on TestBarb; Superiority Dice, 5/5, on a TestFighter; and Emboldening Bond, 4/4, on TestPeace. TestBM's Superiority Dice go from 0/0 to 5/5 and the other TestFighter's from 4/4 to 5/5, which is right for a Battle Master at Fighter 9 (D167). TestPeace knows 5 Cleric cantrips where a level 9 Cleric knows 4: a warning, and its cantrips are left as they are. The job was applied at 09:20 UTC; a second dry run found nothing to change, and all 13 characters and all 15 encounters load through the API. C adds no column: each class's spell numbers and the ability score maximums are worked out when a character is read (D164, D167).

**Done (2026-11-03, workstream D):** V21 is applied to the local database after two backups and a dry run. The backups are `before-r6-v21-20261103T0923Z.sql` and `before-r6-v21-final-20261103T1024Z.sql` in `tabletopHelper-backups/`. The `preview-migration` dry run of V21 examined 276 rows and found 13 characters to change, each gaining `version` 0, none failing to load. V21 was applied at 10:26 UTC, and all 13 characters have version 0. `recompute-characters` then found nothing to change; its only warning is the known one about TestPeace's cantrips. All 13 characters and all 15 encounters load through the API. A rehearsal on a copy of the data gave the same preview and recompute results first (D158). D also unmaps `feat_resources`, which no local character had data in; the column stays until R7 drops it (D168).

**Done (2026-11-03, workstream E):** V22 is applied to the local database after two backups and a dry run. The backups are `before-r6-v22-20261103T1027Z.sql` and `before-r6-v22-final-20261103T1120Z.sql` in `tabletopHelper-backups/`. The `preview-migration` dry run of V22 examined 276 rows and found 13 characters to change, each gaining an empty `dm_adjustments`, none failing to load. V22 was applied at 11:22 UTC, and all 13 characters have an empty list. `recompute-characters` then found nothing to change, and all 13 characters and all 15 encounters load through the API. A rehearsal on a copy of the data gave the same preview and recompute results first (D158). E adds no other column: the stored ability scores and `armour_class` keep the values without adjustments, and the adjusted scores, AC and damage defences are worked out when a character is read (D158, D161).

**Done (2026-11-03, workstream F):** V23 and the `backfill-pending-choices` job are applied to the local database, after three backups and a dry run of each. The backups are `before-r6-v23-20261103T1123Z.sql`, `before-r6-v23-final-20261103T1245Z.sql` and `before-r6-backfill-20261103T1247Z.sql` in `tabletopHelper-backups/`. The `preview-migration` dry run of V23 examined 276 rows and found 13 characters to change, each gaining an empty `pending_choices`, none failing to load. V23 was applied at 12:47 UTC. The job's dry run examined 13 characters and found 8 to change, all soft-deleted test characters: Test gains ASIs at Sorcerer 4 and 8, TEstBarb at Barbarian 8, TestBM at Fighter 6 and 8, TestEcho at Fighter 6, each TestFighter at Fighter 6 and 8, and TestPeace, at level 11, at Cleric 8, and TestBard gains Expertise ×2 at Bard 3. That is 11 missed ASIs on 7 characters and TestBard's Expertise, exactly as the plan predicted. No subclass was missed, and `asdasd` owes nothing. The job was applied and changed those 8, and a second dry run found nothing to change. `recompute-characters` then found nothing to change, and all 13 characters and all 15 encounters load through the API. A rehearsal on a copy of the data gave the same preview, backfill and recompute results first (D159). F adds no other column: the answers to the choices go in `level_history` (D168).

**Done (2026-11-03, workstream G):** V24 is applied to the local database after two backups and a dry run. The backups are `before-r6-v24-20261103T1248Z.sql` and `before-r6-v24-final-20261103T1349Z.sql` in `tabletopHelper-backups/`. The `preview-migration` dry run of V24 examined 276 rows and found 16 to change, none failing to load: asdasd's 3 participants in Live Encounters gain `joined_with` with HP 50, temp HP 0, no slots and Rage at 2/2, and all 13 characters gain `is_dead` false. The 15 encounters gain `results_applied` as NULL, which isn't counted as a change. V24 was applied at 13:51 UTC. `recompute-characters` and `backfill-pending-choices` then found nothing to change, and all 13 characters and all 15 encounters load through the API. Ending asdasd's 3 Live Encounters with their results applied would take its HP from 50 to 0 (−50, 0 and −3), alive, with Rage unchanged. A rehearsal on a copy of the data gave the same preview first, and ending the copy's three encounters, one applied, one declined and one applied, took asdasd from 50 to 47 and then to 0 (D166). A backup taken after R6, `after-r6-20261103T1351Z.sql`, is in the same folder.

**R6 is complete (2026-11-03):** V19 to V24 and both maintenance jobs are applied to the local database. What R7 changes is in the outline below.

**Done (2026-11-05, R7 workstream B):** V25 and V26 are applied to the local database, after backups and a `preview-migration` dry run, which found no character to change. V26 deleted the 14 duplicate Fighting Style copies. On the first start all 12 content loaders ran and changed no other row (D169).

**Done (2026-11-06, R7 workstream D):** V27 is applied to the local database, after a backup (`before-r7-v27-20261106T0235Z.sql`) and a `preview-migration` dry run that found no row to change. `preview-content` then found 35 pool rows to change, and the next start loaded them: 4 definitions added and 26 changed, and 4 class mappings added. After another backup (`before-r7-recompute-20261106T0238Z.sql`), `recompute-characters` changed 9 characters' pools, as on the restored copy, and a second dry run found nothing left (D169).

**Done (2026-11-06, R7 workstream E):** V28 is applied to the local database, after a backup (`before-r7-v28-20261106T0316Z.sql`) and a `preview-migration` dry run that found no row to change. The next start changed only Hill Dwarf's and the Draconic Bloodline's effects and Elemental Adept's, and `recompute-characters` found no character to change (D167).

**Done (2026-11-06, R7 workstream F):** after a backup (`before-r7-f-final-20261106T0355Z.sql`) and a `preview-content` dry run, the next start changed the `action_templates` of the 42 corrected monsters and nothing else (D165). F has no migration.

**R7 workstream G (2026-11-06):** V29 is committed but not yet applied to the local database. Its values are archived in `r7-dropped-columns-20261106T0413Z.csv` (13 characters; only the three hit-dice columns held values) and backed up in `before-r7-v29-final-20261106T0413Z.sql`, and its `preview-migration` dry run listed 39 values on 13 characters, as on the restored copy. The next backend start applies it. The deprecated columns' rows above go once it is applied (D168).

**Done (2026-11-06, R7 G and R8 on the local database):** after a backup (`before-r8-start-20261106T1738Z.sql`), the backend started on Spring Boot 4.1 applied V29, dropping the seven columns marked above, and loaded R8's content: the classes, feats, resource-pools and monster-actions loaders ran. `json-roundtrip` then found its baseline of 11 null-only changes and nothing unreadable, `recompute-characters` found no character to change, and every character and encounter loaded (D150, D168).

### R3–R7 — Outline

| Phase | Area | Expected change | Decision |
|-------|------|-----------------|----------|
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
