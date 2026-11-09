-- M14: the Undo System. Each combat command an Active encounter takes is recorded as a row of combat_commands, in the
-- order taken (sequence, one series per encounter), with the state it changed as it was before: the participants it
-- changed and, when it moved them, the encounter's round, turn marker and lair list (before_state, a typed
-- encounter.state.CommandSnapshot), and any combat log entry it changed, such as the one Legendary Resistance resolves.
-- Undo restores the newest commands' before_state, newest first, and marks them undone; there is no redo. A barrier is
-- a command undo can't go past: the encounter's start, and a participant's removal mid-fight, which has already
-- written to their character sheet. When the encounter completes, before_state is cleared to save space and the rows
-- stay, so the log keeps which of its entries were undone. Each combat log entry names the command that wrote it
-- (command_id); entries written before V32 name none and can't be undone. The log gains UNDO, the entry undo writes,
-- which names the command it undid (undoes_command_id). The preview-migration maintenance job runs this file in a
-- transaction it always rolls back, splitting it into statements, so it has no DO blocks.
CREATE TABLE IF NOT EXISTS combat_commands (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    encounter_id UUID        NOT NULL,
    sequence     BIGINT      NOT NULL,
    kind         VARCHAR(40),
    summary      TEXT,
    issued_by    UUID,
    before_state JSONB,
    barrier      BOOLEAN     NOT NULL DEFAULT FALSE,
    undone_at    TIMESTAMPTZ,
    undone_by    UUID,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_combat_commands_encounter FOREIGN KEY (encounter_id) REFERENCES encounters (id) ON DELETE CASCADE,
    CONSTRAINT uq_combat_commands_encounter_sequence UNIQUE (encounter_id, sequence)
);

-- The newest commands not yet undone, which is all undo reads
CREATE INDEX IF NOT EXISTS idx_combat_commands_undoable
    ON combat_commands (encounter_id, sequence DESC) WHERE undone_at IS NULL;

ALTER TABLE combat_logs ADD COLUMN IF NOT EXISTS command_id UUID;
ALTER TABLE combat_logs DROP CONSTRAINT IF EXISTS fk_combat_logs_command;
ALTER TABLE combat_logs ADD CONSTRAINT fk_combat_logs_command
    FOREIGN KEY (command_id) REFERENCES combat_commands (id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_cl_command_id ON combat_logs (command_id);

ALTER TABLE combat_logs ADD COLUMN IF NOT EXISTS undoes_command_id UUID;
ALTER TABLE combat_logs DROP CONSTRAINT IF EXISTS fk_combat_logs_undoes_command;
ALTER TABLE combat_logs ADD CONSTRAINT fk_combat_logs_undoes_command
    FOREIGN KEY (undoes_command_id) REFERENCES combat_commands (id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_cl_undoes_command_id ON combat_logs (undoes_command_id);

-- V30's types and UNDO
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
        'POOL_USE', 'STANDARD_ACTION', 'OBJECT_INTERACTION', 'TURN_ECONOMY_SET', 'REACTION_TAKEN',
        'UNDO'
    ));
