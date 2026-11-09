-- M26: Summoning (D182). A summoning spell's creatures join the encounter as COMPANION participants: each records the
-- caster whose spell brought it, summoned_by_participant_id, and what ties it to that spell, summon, a typed
-- encounter.state.SummonLink: {effectId, spell, slotLevel, kind, initiativeMode, groupId, variant, creatureType,
-- vanishesAtZero, cannotAttack, needsCommand, commandedRound}. A Tasha's spirit's actions are made for it at the cast,
-- action_templates, in the monsters' template schema; null means its monster row's. A creature that disappears is
-- kept, dismissed, so undo can bring it back. The caster's Spell Effect gains an optional summon field in the JSON
-- itself, so active_effects needs no change, and rows without it read back as they are.
--
-- No existing row changes: the new columns are null or false. The campaigns gain a setting that makes Conjure Animals'
-- creatures the DM's choice (PHB p.225: "The DM has the creatures' statistics"). The combat log gains SUMMON and
-- SUMMON_END. The preview-migration maintenance job runs this file in a transaction it always rolls back, splitting it
-- into statements, so it has no DO blocks.
ALTER TABLE encounter_participants ADD COLUMN IF NOT EXISTS summoned_by_participant_id UUID;
ALTER TABLE encounter_participants ADD COLUMN IF NOT EXISTS summon JSONB;
ALTER TABLE encounter_participants ADD COLUMN IF NOT EXISTS action_templates JSONB;
ALTER TABLE encounter_participants ADD COLUMN IF NOT EXISTS dismissed BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE encounter_participants DROP CONSTRAINT IF EXISTS fk_encounter_participants_summoned_by;
ALTER TABLE encounter_participants ADD CONSTRAINT fk_encounter_participants_summoned_by
    FOREIGN KEY (summoned_by_participant_id) REFERENCES encounter_participants (id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_encounter_participants_summoned_by
    ON encounter_participants (summoned_by_participant_id);

ALTER TABLE campaigns ADD COLUMN IF NOT EXISTS dm_chooses_conjured_creatures BOOLEAN NOT NULL DEFAULT FALSE;

-- V33's types, SUMMON and SUMMON_END
ALTER TABLE combat_logs DROP CONSTRAINT IF EXISTS combat_logs_action_type_check;
ALTER TABLE combat_logs ADD CONSTRAINT combat_logs_action_type_check
    CHECK (action_type IN (
        'ATTACK', 'DAMAGE', 'HEAL',
        'CONDITION_ADD', 'CONDITION_REMOVE',
        'DEATH_SAVE', 'CONCENTRATION_CHECK', 'CONCENTRATION_LOST',
        'TURN_ADVANCE', 'TURN_BACK',
        'STABILIZE', 'KILL', 'REVIVE',
        'SPELL_SLOT_USE', 'SPELL_SLOT_RESTORE', 'SPELL_CAST',
        'SPELL_EFFECT_REPEAT', 'SPELL_EFFECT_END',
        'MONSTER_ACTION', 'LEGENDARY_ACTION', 'LAIR_ACTION',
        'LEGENDARY_RESISTANCE_USED',
        'RECHARGE_SUCCESS', 'RECHARGE_FAILURE',
        'HP_SET', 'REPEAT_SAVE',
        'POOL_USE', 'STANDARD_ACTION', 'OBJECT_INTERACTION', 'TURN_ECONOMY_SET', 'REACTION_TAKEN',
        'UNDO',
        'SUMMON', 'SUMMON_END'
    ));
