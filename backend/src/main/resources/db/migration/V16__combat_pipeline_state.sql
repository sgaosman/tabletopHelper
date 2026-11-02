-- R5 workstream D (D160-D163): the state the one resolve/apply path keeps. The preview-migration maintenance
-- job runs this file in a transaction it always rolls back, splitting it into statements, so it has no DO
-- blocks.

-- ── Concentration Instances (D162) ─────────────────────────────────────────────────────────────────────────
-- concentration_spell and concentration_slot_level become one Concentration Instance, which starts in the
-- encounter's current round; how long the spell lasts isn't recorded, so the DM ends it.
ALTER TABLE encounter_participants ADD COLUMN IF NOT EXISTS concentration JSONB;

UPDATE encounter_participants p
SET concentration = jsonb_build_object(
        'id', gen_random_uuid(),
        'spell', p.concentration_spell,
        'slotLevel', p.concentration_slot_level,
        'startedRound', COALESCE(e.round_number, 1),
        'durationRounds', NULL)
FROM encounters e
WHERE e.id = p.encounter_id
  AND p.concentration_spell IS NOT NULL
  AND btrim(p.concentration_spell) <> '';

ALTER TABLE encounter_participants DROP COLUMN IF EXISTS concentration_spell;
ALTER TABLE encounter_participants DROP COLUMN IF EXISTS concentration_slot_level;

-- ── Hit points and death saves that rules.combat.Vitals accepts (D161) ─────────────────────────────────────
-- At least 1 maximum HP, current HP between 0 and the maximum, no negative temporary HP, and death save
-- counts from 0 to 3
UPDATE encounter_participants SET hp_max = 1 WHERE hp_max < 1;
UPDATE encounter_participants SET hp_current = LEAST(GREATEST(hp_current, 0), hp_max)
WHERE hp_current < 0 OR hp_current > hp_max;
UPDATE encounter_participants SET hp_temp = 0 WHERE hp_temp IS NULL OR hp_temp < 0;
UPDATE encounter_participants
SET death_save_successes = LEAST(GREATEST(COALESCE(death_save_successes, 0), 0), 3),
    death_save_failures = LEAST(GREATEST(COALESCE(death_save_failures, 0), 0), 3)
WHERE death_save_successes IS NULL OR death_save_successes NOT BETWEEN 0 AND 3
   OR death_save_failures IS NULL OR death_save_failures NOT BETWEEN 0 AND 3;

-- ── Life state (D161) ──────────────────────────────────────────────────────────────────────────────────────
-- Alive above 0 HP. At 0, a monster is Dead, and a player character is Dead with three failed death saves,
-- Stable with three successes, and Dying otherwise.
ALTER TABLE encounter_participants ADD COLUMN IF NOT EXISTS life_state VARCHAR(10);

UPDATE encounter_participants
SET life_state = CASE
        WHEN hp_current > 0 THEN 'ALIVE'
        WHEN participant_type <> 'PLAYER' THEN 'DEAD'
        WHEN death_save_failures >= 3 THEN 'DEAD'
        WHEN death_save_successes >= 3 THEN 'STABLE'
        ELSE 'DYING'
    END;

-- A Stable PC's death save counts are reset (PHB p.197)
UPDATE encounter_participants SET death_save_successes = 0, death_save_failures = 0
WHERE life_state = 'STABLE' AND (death_save_successes <> 0 OR death_save_failures <> 0);

ALTER TABLE encounter_participants ALTER COLUMN life_state SET NOT NULL;
ALTER TABLE encounter_participants DROP CONSTRAINT IF EXISTS encounter_participants_life_state_check;
ALTER TABLE encounter_participants ADD CONSTRAINT encounter_participants_life_state_check
    CHECK (life_state IN ('ALIVE', 'DYING', 'STABLE', 'DEAD'));
ALTER TABLE encounter_participants DROP COLUMN IF EXISTS is_alive;

-- ── Condition entries (D162) ───────────────────────────────────────────────────────────────────────────────
-- Every entry gets an ID, and the conditions from before R5 end only by hand. An entry that already has an ID
-- is left alone.
UPDATE encounter_participants p
SET active_conditions = converted.conditions
FROM (
    SELECT ep.id,
           (SELECT jsonb_agg(
                       CASE WHEN jsonb_typeof(c.value) <> 'object' OR c.value -> 'id' IS NOT NULL THEN c.value
                            ELSE c.value || jsonb_build_object('id', gen_random_uuid(),
                                                               'endRule', jsonb_build_object('type', 'MANUAL'))
                       END ORDER BY c.position)
            FROM jsonb_array_elements(ep.active_conditions) WITH ORDINALITY AS c(value, position)) AS conditions
    FROM encounter_participants ep
    WHERE jsonb_typeof(ep.active_conditions) = 'array'
      AND jsonb_array_length(ep.active_conditions) > 0
) AS converted
WHERE p.id = converted.id
  AND p.active_conditions IS DISTINCT FROM converted.conditions;

-- A Dying or Stable PC is unconscious and prone (D161). The unconsciousness ends when it regains hit points;
-- one the PC already has from another source stays as it is.
UPDATE encounter_participants
SET active_conditions = COALESCE(active_conditions, '[]'::jsonb)
        || jsonb_build_array(jsonb_build_object(
               'id', gen_random_uuid(), 'name', 'unconscious', 'duration', NULL, 'appliedRound', 1,
               'sourceSpellName', NULL, 'sourceParticipantId', NULL, 'sourceRequiresConcentration', NULL,
               'concentrationInstanceId', NULL, 'endRule', jsonb_build_object('type', 'HP_REGAINED')))
WHERE life_state IN ('DYING', 'STABLE')
  AND NOT COALESCE(active_conditions @> '[{"name": "unconscious"}]'::jsonb, FALSE);

UPDATE encounter_participants
SET active_conditions = COALESCE(active_conditions, '[]'::jsonb)
        || jsonb_build_array(jsonb_build_object(
               'id', gen_random_uuid(), 'name', 'prone', 'duration', NULL, 'appliedRound', 1,
               'sourceSpellName', NULL, 'sourceParticipantId', NULL, 'sourceRequiresConcentration', NULL,
               'concentrationInstanceId', NULL, 'endRule', jsonb_build_object('type', 'MANUAL')))
WHERE life_state IN ('DYING', 'STABLE')
  AND NOT COALESCE(active_conditions @> '[{"name": "prone"}]'::jsonb, FALSE);

-- ── Combat log (D161, D162, D163) ──────────────────────────────────────────────────────────────────────────
-- Legendary Resistance needs the damage a successful save would have left and the IDs of the conditions the
-- failed save applied. Entries logged before R5 have neither, so the button no longer appears on them.
ALTER TABLE combat_logs ADD COLUMN IF NOT EXISTS lr_success_damage INTEGER;
ALTER TABLE combat_logs ADD COLUMN IF NOT EXISTS lr_condition_ids JSONB;

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
        'RECHARGE_SUCCESS', 'RECHARGE_FAILURE',
        'HP_SET', 'REPEAT_SAVE'
    ));
