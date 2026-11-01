# M12: Monster Actions, Legendary Actions, Legendary Resistance, Lair Actions

## Implementation Instructions for DeepSeek v4 Flash

**Read this entire document before writing any code.**

This document contains every decision, file path, pattern reference, and implementation detail you need. Do not make assumptions. If something is not specified here, ask the user before proceeding.

---

## 0. PREREQUISITES (Already Done Before You Start)

Before you begin implementation, the following has already been completed:

1. The file `backend/src/main/resources/data/monster-actions/monster-action-definitions.json` has been updated to include structured `lairActions` arrays for all 115 legendary groups that have lair actions. The lair actions follow the same schema as regular monster actions (with `deliveryMethod`, `saveDC`, `saveAbility`, `halfOnSave`, `effects`, `targetType`, `aoeSize`, etc.). Lair actions that could not be structured have `automatable: false` with a `description` field.

2. All 2,357 monsters across all CRs have structured action data in that file. There is no CR restriction. You are implementing for ALL monsters.

---

## 1. DOCUMENTATION UPDATE (Do This First)

Before writing any implementation code, update the project documentation to record the decisions made for M12. Follow the rules in `CLAUDE.md`:

- Never rewrite a documentation file entirely. Use targeted append/edit only.
- Ask the user for permission before modifying any documentation file.
- Present the proposed changes and wait for approval.

### 1a. Append to `obsidian-docs/decisions-log.md`

Add the following new decisions at the end of the file (after D127). Use the next available D-number sequence (D128, D129, etc.):

**D128: M12 Scope Expansion — All CRs, M12.5 Eliminated**
- Decision: M12 covers all 2,357 monsters across all CRs, not just CR 0-10. M12.5 is eliminated as a separate milestone. The monster action resolver engine handles all CRs because the data patterns are identical (same delivery methods, effect types, and action structures at all tiers). Only 4 fields are unique to CR 11+ (isEyeRays, rayCount, variableCost, autoHit) and all are tractable edge cases.
- Related: D033

**D129: Monster Action Panel UI — Expandable Accordion on Participant Row**
- Decision: When the DM clicks on a monster participant in the encounter session, the monster's row expands downward (accordion-style) to show its available actions inline in the initiative order. Actions are grouped into sections: Actions (with individual attacks and multiattack combos listed as separate lines), Spells (if the monster has spellcasting), and Legendary Actions (with remaining count and cost per action). Non-automatable actions show their description text and log to the combat log when clicked. The DM adjudicates effects manually using existing damage/heal/condition tools.
- Related: D033, D104

**D130: Monster Spellcasting — Option B with Editable Overrides**
- Decision: Monster spells appear as buttons in the monster action accordion panel (not through the player SpellCastModal). Clicking a spell opens a simplified flow: target selection only (since slot level, save DC, and attack bonus are known from the structured data). The save DC and attack bonus fields are shown as pre-populated editable text boxes so the DM can override them if needed. Monster spells resolve through the existing SpellResolverEngine. Innate spellcasting (per-spell usage limits like "1/day", "3/day") is modelled as ResourcePoolEntry records (e.g., poolId "innate:dispel-magic", maxUses 1, resetOn "longRest"). "At will" spells get no pool (unlimited use).
- Related: D104, D125

**D131: Multiattack — Component Lines with Combo Options**
- Decision: The monster action panel shows both individual component attacks AND multiattack combos as separate clickable lines. Example: a monster with "Multiattack: Longsword + Dagger" shows 3 lines: "Longsword", "Dagger", "Multiattack: Longsword + Dagger". This allows the DM to execute the full multiattack against one target, or split attacks across targets by clicking individual components. When a multiattack has options (e.g., "two longsword OR two longbow attacks"), each option appears as a separate multiattack line.
- Related: D033

**D132: Legendary Actions — Persistent Panel, Usable Any Time**
- Decision: If a monster has legendary actions, its expandable accordion panel always shows a "Legendary Actions" section with the remaining count badge. The DM can click a legendary action at any time during combat (they are not gated by turn). Each legendary action shows its cost. The legendary action pool resets at the start of the monster's turn via the existing ResourcePoolEntry turn-reset mechanism. Legendary actions that reference spellcasting open the monster's spell panel. Non-automatable legendary actions log to combat log with description.
- Related: D125, D127

**D133: Legendary Resistance — Inline Prompt on Failed Save**
- Decision: When a monster with legendary resistance remaining fails a saving throw (from a spell or save-forcing ability), the system returns the failure result along with a prompt: "Use Legendary Resistance? (X remaining)". This prompt appears inline in the combat result (in the response payload). If the DM clicks "Use Legendary Resistance", the save is converted to a success, the resistance count is decremented (via ResourcePoolService.spendPool), and the combat log records both the original failure and the legendary resistance use.
- Related: D125

**D134: Recharge — ResourcePoolEntry with Auto-Roll on Turn Start**
- Decision: Each rechargeable monster action is modelled as a ResourcePoolEntry with maxUses 1, resetOn "turn", and resetCheck matching the recharge range (e.g., "1d6>=5" for Recharge 5-6). When advanceTurn() is called and it becomes a monster's turn, the existing pool reset logic rolls the recharge check automatically. The result is logged to the combat log: "[Monster Name]'s [Action Name] recharges! (rolled X)" on success, "[Monster Name]'s [Action Name] does not recharge (rolled X)" on failure. The recharge pool is created per-action (not per-monster) using the action name in the poolId (e.g., "monster:recharge:fire-breath").
- Related: D125, D127

**D135: Lair Actions — Synthetic Participant at Initiative 20**
- Decision: When a lair-capable monster is added to an encounter and the encounter is started, the DM is prompted "Is [Monster Name] fighting in its lair?" If yes, a synthetic "Lair Actions" participant is inserted into the initiative order at initiative 20 (losing ties, i.e., sorted after any creature that also has initiative 20). When the turn reaches this participant, the DM sees the available lair actions. The combat log attributes lair actions to the monster: "[Monster Name]'s Lair Action activates! [description]". The system enforces the 5e rule that the same lair action cannot be used two rounds in a row (the previously used action is greyed out). Lair action data is pre-structured in monster-action-definitions.json.
- Related: D033

**D136: Non-Automatable Actions — Log and Manual Adjudication**
- Decision: Actions marked automatable: false (or lacking structured effects) display their description text in the accordion panel. When clicked, they log to the combat log as "[Monster Name] uses [Action Name]" with the description. The DM then uses existing manual tools (damage, heal, condition buttons) to adjudicate effects. Categories: teleports, shape change, detect/perception, movement actions, and unique abilities all follow this pattern. Monster summoning is deferred to M26 and just logged for now.
- Related: D033, D036

**D137: Monster Reactions and Bonus Actions — Deferred to M13**
- Decision: Although the monster-action-definitions.json contains reactions (311 monsters) and bonusActions (192 monsters) data, these are not displayed or usable in M12. M13 (Enhanced Action Economy) will implement reactions and bonus actions for both monsters and players together.
- Related: D033

**D138: Monster Action Data Seeding — action_templates Column on Monster Entity**
- Decision: A new action_templates JSONB column is added to the monsters table. The structured action data from monster-action-definitions.json is seeded into this column by a MonsterActionSeeder (following the same pattern as SpellSeeder.seedEffectTemplates() which loads spell-effect-definitions.json into the effect_template column on spells). The resolver reads action templates from the Monster entity at runtime.
- Related: D033, D030

**D139: M12 Test Suite — Full Coverage**
- Decision: M12 includes a full test suite: unit tests for MonsterActionResolverEngine (all delivery methods, multiattack, recharge, legendary actions, lair actions, spellcasting), integration tests for new endpoints, and frontend component tests for the accordion action panel. Target: 60-100+ new tests.
- Related: D102, D126

### 1b. Update `obsidian-docs/feature-roadmap.md`

In the Milestone Status table at the top:
- Change M12 status from "Not started" to "In progress"
- Change M12.5 status from "Not started" to "Eliminated — merged into M12 (D128)"
- In M12's Notes column, add: "All CRs, lair actions included"

### 1c. Update `CONTEXT.md`

Add glossary entry for `MonsterActionTemplate` under a new "## Monster Actions (M12)" heading:
- MonsterActionTemplate: the structured action data stored on monsters.action_templates. Contains actions, legendaryActions, lairActions, spellcasting, traits, recharge data, legendary action/resistance counts. Consumed by MonsterActionResolverEngine at runtime.

---

## 2. DATABASE MIGRATION

Create `backend/src/main/resources/db/migration/V9__monster_action_templates.sql`:

```sql
-- M12: Monster action templates and lair action support

-- Structured action data column on monsters (same pattern as spells.effect_template)
ALTER TABLE monsters ADD COLUMN IF NOT EXISTS action_templates JSONB;

-- Add MONSTER_ACTION and LAIR_ACTION and LEGENDARY_ACTION and LEGENDARY_RESISTANCE_USED to the combat action type CHECK constraint
-- First check the existing constraint name, then drop and recreate
ALTER TABLE combat_log DROP CONSTRAINT IF EXISTS combat_log_action_type_check;
ALTER TABLE combat_log ADD CONSTRAINT combat_log_action_type_check CHECK (
    action_type IN (
        'ATTACK', 'DAMAGE', 'HEAL', 'CONDITION_ADD', 'CONDITION_REMOVE',
        'DEATH_SAVE', 'CONCENTRATION_CHECK', 'CONCENTRATION_LOST',
        'TURN_ADVANCE', 'TURN_BACK', 'STABILIZE', 'KILL', 'REVIVE',
        'SPELL_SLOT_USE', 'SPELL_SLOT_RESTORE', 'SPELL_CAST', 'SPELL_EFFECT_REPEAT',
        'MONSTER_ACTION', 'LEGENDARY_ACTION', 'LAIR_ACTION', 'LEGENDARY_RESISTANCE_USED',
        'RECHARGE_SUCCESS', 'RECHARGE_FAILURE'
    )
);

-- Lair action tracking: which lair action was used last round (to enforce no-repeat rule)
ALTER TABLE encounter_participants ADD COLUMN IF NOT EXISTS last_lair_action_used VARCHAR(200);

-- Flag on encounters to track which monsters are fighting in their lair
-- Stored as JSONB array of monster IDs that are in their lair
ALTER TABLE encounters ADD COLUMN IF NOT EXISTS monsters_in_lair JSONB;
```

---

## 3. BACKEND IMPLEMENTATION

### 3a. Update CombatActionType enum

File: `backend/src/main/java/com/tabletophelper/encounter/CombatActionType.java`

Add to the enum:
```java
MONSTER_ACTION,
LEGENDARY_ACTION,
LAIR_ACTION,
LEGENDARY_RESISTANCE_USED,
RECHARGE_SUCCESS,
RECHARGE_FAILURE
```

### 3b. Update Monster entity

File: `backend/src/main/java/com/tabletophelper/monster/Monster.java`

Add a new field (following the same pattern as the existing `actions`, `traits`, etc. JSONB fields):
```java
@JdbcTypeCode(SqlTypes.JSON)
@Column(name = "action_templates", columnDefinition = "jsonb")
private String actionTemplates;
```

### 3c. Create MonsterActionSeeder

File: `backend/src/main/java/com/tabletophelper/seeder/MonsterActionSeeder.java`

Follow the exact pattern of `SpellSeeder.seedEffectTemplates()`:
1. Read `backend/src/main/resources/data/monster-actions/monster-action-definitions.json`
2. For each entry, find the matching Monster entity by `monsterName` and `source`
3. Set `monster.setActionTemplates(entry.toString())` — store the entire entry as JSONB
4. Save in batches (use `saveAll`)
5. Log count of monsters updated
6. Make this idempotent — only seed if `actionTemplates` is null
7. Register in `DataSeeder` CommandLineRunner alongside existing seeders

**IMPORTANT**: The JSON key in the data file is `monsterName`. The Monster entity field is `name`. Match on BOTH name AND source to avoid collisions (e.g., multiple monsters named "Guard" from different sourcebooks). Look at how `MonsterSeeder` handles the name matching for the pattern.

### 3d. Update EncounterParticipant entity

File: `backend/src/main/java/com/tabletophelper/encounter/EncounterParticipant.java`

Add:
```java
@Column(name = "last_lair_action_used", length = 200)
private String lastLairActionUsed;
```

### 3e. Update Encounter entity

File: `backend/src/main/java/com/tabletophelper/encounter/Encounter.java`

Add:
```java
@JdbcTypeCode(SqlTypes.JSON)
@Column(name = "monsters_in_lair", columnDefinition = "jsonb")
private String monstersInLair;
```

### 3f. Create MonsterActionResolverEngine

File: `backend/src/main/java/com/tabletophelper/encounter/MonsterActionResolverEngine.java`

This is a `@Service` class that interprets monster action templates. Follow the pattern of `SpellResolverEngine.java` closely.

**Input**: The action template JSON (from `monster.getActionTemplates()`), action name, target participant IDs, the encounter, the acting monster participant.

**Output**: A result record similar to `SpellResolverEngine.SpellCastResult` containing: resolved (boolean), description (String), totalDamage, totalHealing, targetResults (list), conditionsInflicted (list), requiresManualResolution (boolean), manualResolutionReason (String).

Create a new record:
```java
public record MonsterActionResult(
    boolean resolved,
    String description,
    int totalDamage,
    int totalHealing,
    List<TargetResult> targetResults,
    boolean requiresManualResolution,
    String manualResolutionReason,
    List<String> conditionsInflicted
) {}

public record TargetResult(
    UUID targetId,
    String targetName,
    int damage,
    int healing,
    boolean savedSuccessfully,
    List<String> conditionsApplied,
    String attackOutcome,  // "HIT", "MISS", "CRITICAL"
    Integer rollValue,
    Integer rollTotal,
    String damageType
) {}
```

**Delivery method handling** (reuse logic from SpellResolverEngine):

- `ATTACK_ROLL`: Roll d20 + attackBonus vs target AC. On hit, roll damage dice. On nat 20 (critical), double damage dice. Apply advantage/disadvantage if specified. Use `DiceRoller` for all rolls.
- `SAVING_THROW`: Target rolls d20 + ability modifier vs saveDC. On failure, apply full effects. If `halfOnSave` is true, apply half damage on success. Use the target's ability modifier from their stats (for monsters: from Monster entity ability scores; for PCs: from PlayerCharacter entity).
- `AUTO_HIT`: Apply effects to all targets without a roll.
- `AUTO_DAMAGE`: Apply damage without a roll (no attack, no save).
- `NONE`: Non-mechanical action. Return `requiresManualResolution = true`.
- `OTHER`: Same as NONE.

**Effect handling**:
- `DAMAGE`: Roll `damageDice`, apply via the existing damage pipeline (which already handles temp HP, unconscious rules, death saves, concentration checks). Call the resistance check method `CombatService.applyResistances()`.
- `CONDITION`: Apply condition with duration using `CombatService.addCondition()` logic. Include `saveToEndEachTurn`, `saveToEndDC`, `saveToEndAbility` if present.
- `HEAL`: Apply healing via existing healing pipeline.
- `DEBUFF` / `BUFF` / `OTHER`: Return `requiresManualResolution = true` with description.

**Multiattack handling**:
- When the action has `isMultiattack: true` and `multiattackComponents` array, resolve each component action sequentially against the provided target(s).
- All component attacks go against the same target(s) passed in the request.
- Each component attack is resolved independently (separate attack rolls, separate damage).
- Aggregate all TargetResults into a single MonsterActionResult.

**Eye ray handling** (Beholder):
- If `isEyeRays: true`, randomly select `rayCount` rays from the `rays` array.
- Each selected ray is resolved as a SAVING_THROW action against the targets.
- Return all results aggregated.

**Recharge tracking**:
- The resolver does NOT handle recharge directly. Recharge is handled by the ResourcePoolEntry system (see section 3h).
- The resolver checks if the action has a `recharge` field and if the corresponding pool has `currentUses > 0` before allowing the action.
- After successful use of a rechargeable action, the resolver calls `ResourcePoolService.spendPool()` to set currentUses to 0.

**Non-automatable actions**:
- If the action has `automatable: false` or has no `effects` array and no `deliveryMethod`, return:
  ```java
  new MonsterActionResult(false, action.description, 0, 0, List.of(), true, 
      "Non-automatable action: DM adjudicates effects manually", List.of())
  ```

### 3g. Create MonsterSpellCastService

File: `backend/src/main/java/com/tabletophelper/encounter/MonsterSpellCastService.java`

This service handles monster spellcasting specifically. It wraps the existing `SpellResolverEngine` but adds monster-specific logic:

1. **Regular spellcasting (slots)**: Read the `spellcasting.slots` and `spellcasting.spellsByLevel` from the monster's action template. When a monster casts a spell:
   - Verify the monster has the spell in its `spellsByLevel`
   - Verify the monster has a slot available at the requested level (check `spellSlotsCurrent` on the participant)
   - Use the monster's `spellcasting.saveDC` and `spellcasting.attackBonus` as defaults (but accept overrides from the request)
   - Delegate to `SpellResolverEngine.resolveSpell()` for the actual resolution
   - Deduct the spell slot

2. **Innate spellcasting**: Read `innateSpellcasting` from the action template. For "X/day" spells:
   - Check the corresponding ResourcePoolEntry (poolId format: `"innate:[spell-name-kebab-case]"`) has `currentUses > 0`
   - After casting, call `ResourcePoolService.spendPool()` to decrement
   - "At will" spells have no pool and can be cast unlimited times

3. **Populating monster spellcasting data on encounter join**: In `EncounterService.addMonsterParticipants()`, when a monster has a `spellcasting` block in its `actionTemplates`:
   - Populate `spellSlotsCurrent` with the monster's spell slots (same JSONB format as player spell slots)
   - Populate `spellAttackBonus` with the monster's spellcasting attack bonus
   - Populate `spellSaveDc` with the monster's spellcasting save DC
   - Populate `spellcastingAbility` with the monster's spellcasting ability
   - Populate `spellsKnown` with the monster's spell list (same JSONB format as player spells known)

### 3h. Update ResourcePoolService.createForMonster()

File: `backend/src/main/java/com/tabletophelper/resourcepool/ResourcePoolService.java`

Enhance `createForMonster()` to create pools from the monster's `actionTemplates` JSON:

1. **Legendary actions**: Read `legendaryActionCount` from action templates. Create a ResourcePoolEntry:
   - poolId: `"monster:legendary-actions"`
   - maxUses: `legendaryActionCount` (from the data, NOT hardcoded to 3 — some monsters have different counts)
   - currentUses: same as maxUses
   - resetOn: `"turn"`
   - Already defined in ResourcePoolSeeder; just override maxUses from data

2. **Legendary resistance**: Read `legendaryResistanceCount` from action templates. Create a ResourcePoolEntry:
   - poolId: `"monster:legendary-resistance"`
   - maxUses: `legendaryResistanceCount`
   - currentUses: same as maxUses
   - resetOn: `"longRest"` (effectively never resets during an encounter)

3. **Rechargeable actions**: Iterate through `actions` array. For each action with a `recharge` field:
   - poolId: `"monster:recharge:[action-name-kebab-case]"` (e.g., `"monster:recharge:fire-breath"`)
   - displayName: action name
   - maxUses: 1
   - currentUses: 1 (starts available)
   - resetOn: `"turn"`
   - resetCheck: Convert recharge value to expression:
     - `"5-6"` becomes `"1d6>=5"`
     - `"6"` becomes `"1d6>=6"`
     - `"4-6"` becomes `"1d6>=4"`

4. **Innate spell uses**: If the monster has innate spellcasting, for each spell with a daily use limit:
   - poolId: `"innate:[spell-name-kebab-case]"` (e.g., `"innate:dispel-magic"`)
   - displayName: spell name
   - maxUses: the daily limit (1, 2, or 3)
   - currentUses: same as maxUses
   - resetOn: `"longRest"`
   - "At will" spells get NO pool entry

### 3i. Update advanceTurn() for Recharge Logging

File: `backend/src/main/java/com/tabletophelper/encounter/CombatService.java`

The existing `advanceTurn()` method already calls `ResourcePoolService.resetPools()` which handles turn-based resets and evaluates `resetCheck` expressions. However, the reset results are not currently logged to the combat log.

Modify the turn advance logic so that when a MONSTER participant's turn starts:

1. Before calling `resetPools()`, capture the current state of all pools with `resetOn == "turn"` and `resetCheck != null`
2. Call `resetPools()` as normal
3. Compare before/after states. For each pool that had `currentUses == 0`:
   - If it now has `currentUses == 1`: log `RECHARGE_SUCCESS` to combat log with description: `"[Monster Name]'s [Pool DisplayName] recharges! (rolled X)"`
   - If it still has `currentUses == 0`: log `RECHARGE_FAILURE` to combat log with description: `"[Monster Name]'s [Pool DisplayName] does not recharge (rolled X)"`
4. This requires `resetPools()` to return the roll values. Modify `ResourcePoolService.resetPools()` to return a `List<RechargeResult>` record containing `poolId`, `displayName`, `success` (boolean), `rollValue` (int).

**Note**: The existing `ExpressionEvaluator.evaluateRechargeCheck()` returns only a boolean. You will need to add a variant method `evaluateRechargeCheckWithRoll()` that returns both the boolean result and the actual dice roll value, so it can be logged.

### 3j. Legendary Resistance Integration

When a monster fails a saving throw (in `SpellResolverEngine.resolveSpell()` or `MonsterActionResolverEngine` save resolution), check if:
1. The target is a MONSTER participant
2. The target has a `"monster:legendary-resistance"` pool with `currentUses > 0`

If both are true, include in the response a `legendaryResistanceAvailable: true` flag and `legendaryResistanceRemaining: X` count.

Create a new endpoint:

```
POST /api/encounters/{id}/combat/legendary-resistance
Body: { "participantId": UUID, "combatLogId": UUID }
```

This endpoint:
1. Finds the combat log entry for the failed save
2. Decrements the legendary resistance pool via `ResourcePoolService.spendPool()`
3. Re-resolves the save as a success (reverting any damage/conditions that were applied)
4. Logs `LEGENDARY_RESISTANCE_USED` to combat log: `"[Monster Name] uses Legendary Resistance to succeed on the saving throw! (X remaining)"`
5. Broadcasts updated encounter state via WebSocket

**Alternative simpler approach** (preferred if re-resolving the save is too complex): Instead of re-resolving, make the SpellResolverEngine and MonsterActionResolverEngine NOT apply effects to the target when legendary resistance is available. Instead, return the failed save result with a `pendingLegendaryResistance: true` flag. The frontend shows the prompt. If the DM clicks "Use LR", call the legendary-resistance endpoint which then applies the success path. If the DM clicks "Accept Failure", call a confirm endpoint which applies the failure path (damage + conditions). This two-step approach avoids needing to undo already-applied effects.

**Use the two-step approach.** It is cleaner and avoids undo complexity.

### 3k. Lair Action Participant Creation

Modify `EncounterService`:

1. Add a new endpoint or modify the encounter start flow:
   ```
   POST /api/encounters/{id}/set-lair-status
   Body: { "monsterIds": [UUID, UUID, ...] }
   ```
   This stores which monsters are fighting in their lair on the encounter's `monstersInLair` JSONB field.

2. When the encounter transitions to ACTIVE status (in `startEncounter()` or equivalent):
   - Check `monstersInLair` for any monster IDs
   - For each lair monster, look up its lair actions from its `actionTemplates`
   - Create a synthetic EncounterParticipant:
     - participantType: `MONSTER` (or a new type if preferred, but MONSTER is simpler)
     - displayName: `"[Monster Name]'s Lair Actions"`
     - initiative: 20
     - sortOrder: positioned AFTER any real participants with initiative 20 (losing ties)
     - hpMax / hpCurrent: 9999 (unkillable)
     - armourClass: 0 (irrelevant)
     - monster: set to the lair monster (so we can read its action templates)
     - isVisibleToPlayers: true
     - Add a metadata marker to distinguish this as a lair action participant (e.g., set `notes` to `"LAIR_ACTION_PARTICIPANT"` or add a boolean field)

3. Track the `lastLairActionUsed` field on this lair participant. After each use, set it. When presenting available lair actions, grey out (exclude) the one matching `lastLairActionUsed`. Reset `lastLairActionUsed` to null at the start of each round (when round counter increments in `advanceTurn()`).

### 3l. Monster Action Endpoint

File: `backend/src/main/java/com/tabletophelper/encounter/CombatController.java`

Add new endpoints:

```java
// Execute a monster action (regular action, legendary action, or lair action)
POST /api/encounters/{id}/combat/monster-action
Body: {
    "monsterParticipantId": UUID,    // the acting monster
    "actionName": String,             // name of the action from the template
    "actionSource": String,           // "ACTION", "LEGENDARY", "LAIR"
    "targetParticipantIds": [UUID],   // targets
    "overrideAttackBonus": Integer,   // optional DM override
    "overrideSaveDC": Integer,        // optional DM override
    "advantage": Boolean              // optional advantage/disadvantage
}
Response: MonsterActionResponse (similar to CastSpellResponse)

// Execute a monster spell cast
POST /api/encounters/{id}/combat/monster-spell
Body: {
    "monsterParticipantId": UUID,
    "spellName": String,
    "slotLevel": Integer,
    "targetParticipantIds": [UUID],
    "overrideAttackBonus": Integer,   // pre-populated from data, editable
    "overrideSaveDC": Integer,        // pre-populated from data, editable
    "advantage": Boolean
}
Response: CastSpellResponse (reuse existing)

// Use legendary resistance (two-step: after a failed save)
POST /api/encounters/{id}/combat/legendary-resistance
Body: { "participantId": UUID }

// Confirm failed save (DM declines legendary resistance)
POST /api/encounters/{id}/combat/confirm-failed-save
Body: { "participantId": UUID }

// Set lair status before encounter start
POST /api/encounters/{id}/lair-status
Body: { "monsterIds": [UUID] }

// Get monster action templates for a participant (frontend reads this for the accordion panel)
GET /api/encounters/{id}/participants/{participantId}/actions
Response: the action_templates JSON for the monster, plus current resource pool states (recharge available, legendary actions remaining, etc.)
```

All mutation endpoints must broadcast updated encounter state via WebSocket after completing (follow the pattern of every other combat endpoint in `CombatController`).

### 3m. Wire Monster Action Flow into CombatService

Add a `monsterAction()` method to `CombatService` that:

1. Validates the requesting user is the DM
2. Loads the monster participant and its action templates
3. Finds the requested action by name in the appropriate action list (actions, legendaryActions, or lairActions)
4. For LEGENDARY actions: checks and decrements the legendary action pool
5. For LAIR actions: checks the no-repeat rule (lastLairActionUsed) and updates it
6. For rechargeable actions: checks the recharge pool has currentUses > 0
7. Delegates to `MonsterActionResolverEngine` for resolution
8. If resolved:
   - Applies damage via the existing damage pipeline (temp HP, unconscious, death saves, concentration checks, resistances)
   - Applies conditions with source tracking
   - Applies healing
9. Logs to combat log with the appropriate action type (MONSTER_ACTION, LEGENDARY_ACTION, or LAIR_ACTION)
10. Saves and broadcasts

For the legendary resistance two-step flow, add `pendingSaveResult` tracking. When a monster fails a save and has legendary resistance available:
- Store the pending result temporarily (in-memory map keyed by encounter+participant, or on the participant as transient state)
- Return the result with `pendingLegendaryResistance: true`
- Wait for either `legendary-resistance` or `confirm-failed-save` endpoint call
- Then apply the appropriate path (success or failure)

---

## 4. FRONTEND IMPLEMENTATION

### 4a. New API Methods

File: `frontend/src/api/combatApi.ts`

Add methods:
```typescript
monsterAction(encounterId: string, request: MonsterActionRequest) {
    return api.post<MonsterActionResponse>(`/encounters/${encounterId}/combat/monster-action`, request);
},

monsterSpell(encounterId: string, request: MonsterSpellRequest) {
    return api.post<CastSpellResponse>(`/encounters/${encounterId}/combat/monster-spell`, request);
},

useLegendaryResistance(encounterId: string, participantId: string) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/legendary-resistance`, { participantId });
},

confirmFailedSave(encounterId: string, participantId: string) {
    return api.post<Encounter>(`/encounters/${encounterId}/combat/confirm-failed-save`, { participantId });
},

getMonsterActions(encounterId: string, participantId: string) {
    return api.get<MonsterActionTemplates>(`/encounters/${encounterId}/participants/${participantId}/actions`);
},

setLairStatus(encounterId: string, monsterIds: string[]) {
    return api.post<Encounter>(`/encounters/${encounterId}/lair-status`, { monsterIds });
},
```

### 4b. New TypeScript Types

File: `frontend/src/types/combat.ts`

Add types for:
- `MonsterActionRequest` — matches the backend request body
- `MonsterActionResponse` — similar to `CastSpellResponse` but with `pendingLegendaryResistance` flag
- `MonsterActionTemplates` — the full action template structure (actions, legendaryActions, lairActions, spellcasting, traits, resource pool states)
- `MonsterAction` — individual action entry (name, actionType, deliveryMethod, attackBonus, etc.)
- `MonsterSpellRequest` — monster spellcasting request
- `MonsterSpellcasting` — spellcasting block (ability, saveDC, attackBonus, slots, spellsByLevel)

Model these types to exactly match the JSON structure in `monster-action-definitions.json`.

### 4c. Monster Action Accordion Panel

File: `frontend/src/components/encounter/MonsterActionPanel.tsx` (new file)

This is the expandable accordion that appears when the DM clicks on a monster participant row.

**Structure**:
- Fetch action templates via `getMonsterActions()` when the accordion opens
- Display sections:

**Actions section**:
- List each action from the `actions` array
- For automatable actions with `deliveryMethod` of ATTACK_ROLL: show as a clickable row with the action name, attack bonus, damage dice, and damage type pre-displayed. Clicking opens the existing attack form at the top of the page, pre-populated with the correct values.
- For automatable actions with SAVING_THROW delivery: show as a clickable row. Clicking opens a target selector, then auto-resolves.
- For multiattack entries (`isMultiattack: true`): show as a highlighted row listing the components (e.g., "Multiattack: Bite + Claw + Claw"). Clicking resolves all components against the selected target(s).
- Individual component attacks are ALSO listed as separate clickable rows so the DM can split attacks.
- For rechargeable actions: show a recharge indicator. If the recharge pool has `currentUses == 0`, grey out the action and show "(Recharging)". If `currentUses == 1`, show "(Available)".
- For non-automatable actions: show the action name with an info icon. Clicking logs to combat log and shows the description text.

**Spells section** (only if `spellcasting` exists in the template):
- Show spell slots remaining (current/max per level) — visible to DM only
- List spells grouped by level (cantrips, 1st, 2nd, etc.)
- Each spell is a clickable button
- Clicking opens a simplified casting flow:
  1. Show save DC and attack bonus as pre-populated editable text inputs
  2. Show slot level selector (for non-cantrips, with upcasting)
  3. Show target selector
  4. Confirm and resolve via `monsterSpell()` endpoint
- For innate spells with daily limits: show remaining uses. Grey out when exhausted.
- For "at will" spells: show "(At Will)" tag, always available.

**Legendary Actions section** (only if `legendaryActions` exists):
- Show remaining count badge: "Legendary Actions: X/Y"
- List each legendary action with its cost
- Each is clickable. Clicking:
  1. Checks remaining legendary actions >= cost
  2. For automatable ones: opens target selector, resolves
  3. For non-automatable ones (especially "Cast a Spell" or "Melee Attack" references): either opens the spell panel or pre-populates the attack form for the referenced action
  4. Decrements the legendary action pool

**Styling**:
- Follow the existing Tourmaline design system tokens from `index.css`
- Use `--color-monster` (#991B1B) for monster-related accents
- Use Cinzel for section headings, Cormorant Garamond for body text
- Square corners (global override already in place)
- Use warm parchment surfaces for the expanded panel background

### 4d. Lair Action Prompt on Encounter Start

File: `frontend/src/pages/dm/EncounterBuilderPage.tsx`

When the DM clicks "Start Encounter":
1. Check if any monster participants have `hasLairActions: true` in their action templates
2. If yes, show a modal: "The following monsters have lair actions. Select which are fighting in their lair:"
3. Show a checkbox for each lair-capable monster
4. On confirm, call `setLairStatus()` with the selected monster IDs
5. Then proceed with starting the encounter

If no monsters have lair actions, skip the prompt and start normally.

### 4e. Lair Action Participant Rendering

File: `frontend/src/pages/dm/EncounterSessionPage.tsx`

The lair action synthetic participant appears in the initiative order like any other participant. When its turn comes:
- The participant row shows "[Monster Name]'s Lair Actions" with a distinct visual treatment (e.g., different background color, a castle/tower icon)
- The expandable panel shows available lair actions
- The previously used lair action (if any) is greyed out with "(Used last round)" text
- Clicking an available lair action resolves it via `monsterAction()` with `actionSource: "LAIR"`

### 4f. Legendary Resistance Inline Prompt

When a `MonsterActionResponse` or `CastSpellResponse` comes back with `pendingLegendaryResistance: true`:
- Show the save failure result as normal
- Below it, show a prominent prompt: "Use Legendary Resistance? (X remaining)" with two buttons: "Use Legendary Resistance" and "Accept Failure"
- "Use Legendary Resistance" calls `useLegendaryResistance()` — the response will contain the updated encounter state with the save converted to success
- "Accept Failure" calls `confirmFailedSave()` — the response will contain the updated encounter state with the failure effects applied

This prompt should appear in the result view of whatever modal or panel triggered the save (MonsterActionPanel result view, SpellCastModal result view, etc.).

### 4g. Recharge Combat Log Entries

File: `frontend/src/pages/dm/EncounterSessionPage.tsx` (and player equivalent)

The combat log already renders entries by `actionType`. Add rendering for the new types:
- `RECHARGE_SUCCESS`: Style in green/positive. Show: "[Monster]'s [Action] recharges! (rolled X)"
- `RECHARGE_FAILURE`: Style in muted/grey. Show: "[Monster]'s [Action] does not recharge (rolled X)"
- `MONSTER_ACTION`: Style in monster red (`--color-monster`). Show the action resolution details.
- `LEGENDARY_ACTION`: Style in monster red with a crown icon. Show cost and resolution.
- `LAIR_ACTION`: Style with a distinct color (suggest dark purple or brown). Show the lair action details.
- `LEGENDARY_RESISTANCE_USED`: Style in gold/warning. Show: "[Monster] uses Legendary Resistance! (X remaining)"

---

## 5. TESTING

### 5a. Backend Unit Tests

**MonsterActionResolverEngineTest.java** (target: 30-40 tests):
- ATTACK_ROLL delivery: hit, miss, critical hit, advantage, disadvantage
- SAVING_THROW delivery: pass, fail, half damage on save, no damage on save
- AUTO_HIT delivery: damage applied to all targets
- AUTO_DAMAGE delivery: damage applied without roll
- NONE delivery: returns requiresManualResolution
- DAMAGE effect: correct dice rolling, damage type preserved
- CONDITION effect: condition applied with duration, save-to-end fields preserved
- HEAL effect: healing applied correctly
- Multiattack: all components resolved, results aggregated
- Multiattack with options: correct option selected
- Eye rays (Beholder): correct number of rays selected, each resolved
- Non-automatable action: returns manual resolution with description
- Recharge check: action blocked when pool is empty, allowed when available
- Legendary action: pool decremented on use, blocked when pool empty, cost checked
- Lair action: no-repeat enforcement (same action blocked two rounds in a row)
- Damage resistance: monster resistance/immunity/vulnerability applied correctly
- Target with conditions affecting saves (e.g., restrained gives disadvantage on DEX saves)

**MonsterSpellCastServiceTest.java** (target: 10-15 tests):
- Regular spellcasting: slot deduction, spell level validation, spell list validation
- Innate spellcasting: pool deduction, at-will unlimited
- Override save DC and attack bonus
- Spell not in monster's list: rejected
- No slots remaining: rejected

**CombatServiceMonsterTest.java** (target: 15-20 tests):
- Monster action full flow: action request -> resolve -> damage applied -> conditions applied -> combat log entry
- Legendary action: pool decrement, blocked when empty
- Legendary resistance two-step: pending state, use LR, confirm failure
- Lair action: no-repeat enforcement, combat log attribution to monster
- Recharge logging: success and failure entries in combat log with correct description and roll value
- Multiattack damage aggregation
- advanceTurn with recharge roll: pool reset, combat log entries

**MonsterActionSeederTest.java** (target: 5-8 tests):
- Seeder loads data and sets actionTemplates on correct monsters
- Matching by name + source
- Idempotent: running twice doesn't duplicate
- Monster without action data: actionTemplates stays null

### 5b. Frontend Tests

**MonsterActionPanel.test.tsx** (target: 15-20 tests):
- Renders action list from template data
- Automatable action click triggers API call
- Non-automatable action click logs description
- Multiattack line shows component names
- Rechargeable action greyed out when pool empty
- Rechargeable action available when pool has uses
- Spell section shows slots and spell list
- Innate spell shows remaining uses
- At-will spell has no use limit
- Legendary action section shows remaining count
- Legendary action blocked when pool empty
- Legendary action cost checked against remaining
- Lair action shows available options
- Lair action greys out previously used action
- Save DC and attack bonus fields are editable
- Override values are sent in request

**LairActionPrompt.test.tsx** (target: 5-8 tests):
- Prompt appears when lair-capable monsters exist
- Prompt does not appear when no lair monsters
- Checkbox selection works
- Confirm sends correct monster IDs
- Encounter starts after confirm

---

## 6. IMPLEMENTATION ORDER

Execute in this exact order to minimize merge conflicts and ensure each step builds on the previous:

1. Documentation updates (section 1) — get user approval first
2. Database migration V9 (section 2)
3. Update enums and entities: CombatActionType, Monster, EncounterParticipant, Encounter (sections 3a-3e)
4. MonsterActionSeeder (section 3c) — verify data loads correctly
5. Update ResourcePoolService.createForMonster() (section 3h) — enhanced pool creation
6. Update ExpressionEvaluator for roll-value return (section 3i prerequisite)
7. Update advanceTurn() for recharge logging (section 3i)
8. MonsterActionResolverEngine (section 3f) — core resolver
9. MonsterSpellCastService (section 3g)
10. Legendary resistance two-step flow (section 3j)
11. Lair action participant creation (section 3k)
12. New endpoints in CombatController (section 3l)
13. Wire everything in CombatService (section 3m)
14. Backend tests (section 5a) — verify everything works before touching frontend
15. Frontend types and API methods (sections 4a-4b)
16. MonsterActionPanel component (section 4c)
17. Lair action prompt on encounter start (section 4d)
18. Lair action participant rendering (section 4e)
19. Legendary resistance inline prompt (section 4f)
20. Combat log new entry types (section 4g)
21. Frontend tests (section 5b)
22. Final verification: compile backend (0 errors), build frontend (vite build passes), run all tests (all pass)

---

## 7. KEY FILES TO READ BEFORE STARTING

Read these files to understand the existing patterns. Do NOT modify them unless instructed above:

| File | Why |
|---|---|
| `backend/src/main/java/com/tabletophelper/encounter/SpellResolverEngine.java` | The pattern to follow for MonsterActionResolverEngine |
| `backend/src/main/java/com/tabletophelper/encounter/CombatService.java` | Where monster action/spell flows integrate |
| `backend/src/main/java/com/tabletophelper/encounter/CombatController.java` | Endpoint pattern and WebSocket broadcast pattern |
| `backend/src/main/java/com/tabletophelper/resourcepool/ResourcePoolService.java` | Pool creation, spending, recovery, reset |
| `backend/src/main/java/com/tabletophelper/resourcepool/ExpressionEvaluator.java` | Recharge check evaluation |
| `backend/src/main/java/com/tabletophelper/character/dto/ResourcePoolEntry.java` | Pool entry record structure |
| `backend/src/main/java/com/tabletophelper/seeder/ResourcePoolSeeder.java` | Monster pool definitions and triggers |
| `backend/src/main/java/com/tabletophelper/encounter/EncounterService.java` | How monsters join encounters |
| `backend/src/main/java/com/tabletophelper/encounter/EncounterParticipant.java` | Participant entity fields |
| `backend/src/main/java/com/tabletophelper/encounter/Encounter.java` | Encounter entity fields |
| `backend/src/main/java/com/tabletophelper/monster/Monster.java` | Monster entity fields |
| `backend/src/main/java/com/tabletophelper/encounter/DiceRoller.java` | Dice rolling utility |
| `backend/src/main/resources/data/monster-actions/monster-action-definitions.json` | The data being consumed (read first 500 lines to understand structure) |
| `frontend/src/pages/dm/EncounterSessionPage.tsx` | Where the accordion panel integrates |
| `frontend/src/api/combatApi.ts` | Existing combat API pattern |
| `frontend/src/types/combat.ts` | Existing combat types |
| `frontend/src/types/encounter.ts` | Encounter/participant types |
| `frontend/src/components/encounter/SpellCastModal.tsx` | Pattern for multi-step combat modals |
| `frontend/src/index.css` | Design system tokens (colors, fonts, surfaces) |
| `frontend/src/utils/classColours.ts` | Color utility pattern |
| `CLAUDE.md` | Documentation discipline rules |
| `CONTEXT.md` | Domain glossary |
| `obsidian-docs/decisions-log.md` | Existing decisions for context |

---

## 8. CRITICAL CONSTRAINTS

1. **Do not modify obsidian-docs files with Write/create. Use Edit (append-only) for additions.** See CLAUDE.md.
2. **Do not rewrite any existing file entirely.** Make targeted additions.
3. **No AI attribution in commits.** No Co-Authored-By or Signed-off-by trailers.
4. **Java 21, Spring Boot 3, Gradle Kotlin DSL.** Do not change build tool versions.
5. **React 19, TypeScript, Vite 8, Tailwind CSS 4.** Do not change frontend tool versions.
6. **Flyway migrations are append-only.** Never modify existing V0-V8 migrations. Only add V9+.
7. **All JSONB columns use `@JdbcTypeCode(SqlTypes.JSON)` and `columnDefinition = "jsonb"`.** Follow existing entity patterns.
8. **WebSocket broadcast after every mutation.** Every combat endpoint must call the broadcast method after saving. Check CombatController for the pattern.
9. **Monster reactions and bonus actions are NOT in scope for M12.** Do not render or handle them. They are M13.
10. **Mythic actions are NOT in scope.** Ignore them entirely.
11. **Follow the Tourmaline design system.** See `.claude/DESIGN_SYSTEM.md` and `frontend/src/index.css` for all design tokens. No dark mode. Square corners. Cinzel headings. Cormorant Garamond body.
12. **Tests use existing test infrastructure.** Backend: JUnit 5 + Mockito. Frontend: Vitest + React Testing Library + MSW. See `frontend/src/test/test-utils.tsx` for the custom render wrapper.
