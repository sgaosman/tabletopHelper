-- R6 workstream E (D158, D161): DM Adjustments, the named changes the DM of a character's campaign makes on top of
-- what the rules give it: a stat raised or lowered, a damage resistance, immunity or vulnerability, or a feat granted
-- outside an Ability Score Improvement. Each entry is a character.state.DmAdjustment, oldest first. A removed entry
-- stays, with who removed it and when, as the record; only the active ones count. Existing characters start with none.
-- The preview-migration maintenance job runs this file in a transaction it always rolls back, splitting it into
-- statements, so it has no DO blocks.
ALTER TABLE player_characters ADD COLUMN IF NOT EXISTS dm_adjustments JSONB NOT NULL DEFAULT '[]'::jsonb;
