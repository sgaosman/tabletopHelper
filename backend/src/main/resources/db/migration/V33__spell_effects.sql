-- M15: Persistent Spell Effects. A caster's lasting spell effects (Spiritual Weapon's weapon, Moonbeam's beam, Spirit
-- Guardians' spirits) become a list of Spell Effects on the caster's participant row, encounter_participants
-- .active_effects, each a typed encounter.state.SpellEffectEntry: {id, spell, slotLevel, concentrationInstanceId,
-- startedRound, durationRounds, usesLeft}. It replaces active_spell and active_spell_slot_level (V6), which held one
-- spell kept up without concentration.
--
-- Existing rows are converted: an active_spell becomes an effect with no Concentration Instance, started in its
-- encounter's current round and lasting Spiritual Weapon's 10 rounds (PHB p.278; it was the only spell that set the
-- column), and a live Concentration Instance on one of the spells with a lasting effect gains an effect linked to it,
-- with the instance's own round and duration, so the caster can still use it. Melf's Minute Meteors starts with six
-- meteors and two more for each slot level above 3rd (XGE p.161). The combat log gains SPELL_EFFECT_END. The
-- preview-migration maintenance job runs this file in a transaction it always rolls back, splitting it into
-- statements, so it has no DO blocks.
ALTER TABLE encounter_participants ADD COLUMN IF NOT EXISTS active_effects JSONB NOT NULL DEFAULT '[]'::jsonb;

-- The spell kept up without concentration
UPDATE encounter_participants p
SET active_effects = p.active_effects || jsonb_build_array(jsonb_build_object(
        'id', gen_random_uuid(),
        'spell', p.active_spell,
        'slotLevel', p.active_spell_slot_level,
        'concentrationInstanceId', NULL,
        'startedRound', GREATEST(COALESCE(e.round_number, 1), 1),
        'durationRounds', CASE WHEN lower(p.active_spell) = 'spiritual weapon' THEN 10 END,
        'usesLeft', NULL))
FROM encounters e
WHERE e.id = p.encounter_id
  AND p.active_spell IS NOT NULL
  AND btrim(p.active_spell) <> '';

-- A live Concentration Instance on a spell with a lasting effect
UPDATE encounter_participants p
SET active_effects = p.active_effects || jsonb_build_array(jsonb_build_object(
        'id', gen_random_uuid(),
        'spell', p.concentration ->> 'spell',
        'slotLevel', CASE WHEN jsonb_typeof(p.concentration -> 'slotLevel') = 'number'
                          THEN (p.concentration ->> 'slotLevel')::int END,
        'concentrationInstanceId', p.concentration -> 'id',
        'startedRound', CASE WHEN jsonb_typeof(p.concentration -> 'startedRound') = 'number'
                             THEN (p.concentration ->> 'startedRound')::int ELSE 1 END,
        'durationRounds', CASE WHEN jsonb_typeof(p.concentration -> 'durationRounds') = 'number'
                               THEN (p.concentration ->> 'durationRounds')::int END,
        'usesLeft', CASE WHEN lower(p.concentration ->> 'spell') = 'melf''s minute meteors'
                         THEN 6 + 2 * GREATEST(COALESCE(CASE WHEN jsonb_typeof(p.concentration -> 'slotLevel') = 'number'
                                                             THEN (p.concentration ->> 'slotLevel')::int END, 3) - 3, 0)
                    END))
WHERE jsonb_typeof(p.concentration) = 'object'
  AND jsonb_typeof(p.concentration -> 'id') = 'string'
  AND lower(p.concentration ->> 'spell') IN (
        'aura of vitality', 'call lightning', 'cloud of daggers', 'create bonfire', 'dust devil',
        'ensnaring strike', 'flame blade', 'flaming sphere', 'heat metal', 'hunger of hadar',
        'maximilian''s earthen grasp', 'melf''s minute meteors', 'moonbeam', 'phantasmal force',
        'searing smite', 'spirit guardians', 'tasha''s caustic brew', 'vampiric touch', 'witch bolt');

ALTER TABLE encounter_participants DROP COLUMN IF EXISTS active_spell;
ALTER TABLE encounter_participants DROP COLUMN IF EXISTS active_spell_slot_level;

-- V32's types and SPELL_EFFECT_END
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
        'UNDO'
    ));
