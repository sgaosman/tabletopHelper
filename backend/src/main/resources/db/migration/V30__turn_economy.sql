-- M13 workstream A (D174): the action economy. Each participant's turn economy is one typed record (D168),
-- encounter.state.TurnEconomy, in encounter_participants.turn_economy: its actions and how many it has taken, the
-- Attack action and the attacks left in it, the bonus action, the reaction, the free object interaction, the
-- bonus-action-spell rule's two flags, and the Dash and Disengage badges. The default is the fresh record a turn starts
-- with. It replaces V8's action_used, bonus_action_used and reaction_used, which were reset at each turn's start but
-- never set, amending D125; a participant with one set keeps it. The combat log gains the types the economy's commands
-- write. The preview-migration maintenance job runs this file in a transaction it always rolls back, splitting it into
-- statements, so it has no DO blocks.
ALTER TABLE encounter_participants ADD COLUMN IF NOT EXISTS turn_economy JSONB NOT NULL DEFAULT
    '{"actions": 1, "actionsUsed": 0, "attackActionTaken": false, "attacksLeft": 0, "bonusActionUsed": false,
      "reactionUsed": false, "objectInteractionUsed": false, "bonusActionSpellCast": false, "otherSpellCast": false,
      "dashed": false, "disengaged": false}'::jsonb;

-- Whatever the three flags held: the action as one action taken
UPDATE encounter_participants
SET turn_economy = turn_economy || jsonb_build_object(
        'actionsUsed', CASE WHEN action_used THEN 1 ELSE 0 END,
        'bonusActionUsed', COALESCE(bonus_action_used, FALSE),
        'reactionUsed', COALESCE(reaction_used, FALSE))
WHERE action_used OR bonus_action_used OR reaction_used;

ALTER TABLE encounter_participants DROP COLUMN IF EXISTS action_used;
ALTER TABLE encounter_participants DROP COLUMN IF EXISTS bonus_action_used;
ALTER TABLE encounter_participants DROP COLUMN IF EXISTS reaction_used;

-- The combat log types of the action economy's commands: a pool used, a standard action, an object interaction, the
-- DM's edit of a turn economy and a reaction taken
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
        'HP_SET', 'REPEAT_SAVE',
        'POOL_USE', 'STANDARD_ACTION', 'OBJECT_INTERACTION', 'TURN_ECONOMY_SET', 'REACTION_TAKEN'
    ));
