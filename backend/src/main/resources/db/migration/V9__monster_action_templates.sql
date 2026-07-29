-- M12: Monster action templates and lair action support

-- Structured action data column on monsters (same pattern as spells.effect_template)
ALTER TABLE monsters ADD COLUMN IF NOT EXISTS action_templates JSONB;

-- Add MONSTER_ACTION, LEGENDARY_ACTION, LAIR_ACTION, LEGENDARY_RESISTANCE_USED,
-- RECHARGE_SUCCESS, RECHARGE_FAILURE to the combat action type CHECK constraint
ALTER TABLE combat_logs DROP CONSTRAINT IF EXISTS combat_logs_action_type_check;
ALTER TABLE combat_logs ADD CONSTRAINT combat_logs_action_type_check
    CHECK (action_type IN (
        'ATTACK', 'DAMAGE', 'HEAL',
        'CONDITION_ADD', 'CONDITION_REMOVE',
        'DEATH_SAVE', 'CONCENTRATION_CHECK', 'CONCENTRATION_LOST',
        'TURN_ADVANCE', 'TURN_BACK',
        'STABILIZE', 'KILL', 'REVIVE',
        'SPELL_SLOT_USE', 'SPELL_SLOT_RESTORE', 'SPELL_CAST',
        'SPELL_EFFECT_REPEAT',
        'MONSTER_ACTION', 'LEGENDARY_ACTION', 'LAIR_ACTION',
        'LEGENDARY_RESISTANCE_USED',
        'RECHARGE_SUCCESS', 'RECHARGE_FAILURE'
    ));

-- Lair action tracking: which lair action was used last round (to enforce no-repeat rule)
ALTER TABLE encounter_participants ADD COLUMN IF NOT EXISTS last_lair_action_used VARCHAR(200);

-- Flag on encounters to track which monsters are fighting in their lair
-- Stored as JSONB array of monster IDs that are in their lair
ALTER TABLE encounters ADD COLUMN IF NOT EXISTS monsters_in_lair JSONB;
