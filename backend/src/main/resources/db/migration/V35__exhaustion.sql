-- Exhaustion (D183, PHB p.291). Exhaustion comes in six levels, each adding its effect to those below. A player
-- character's level outlasts encounters, so it belongs on the sheet: player_characters.exhaustion_level, which a long
-- rest lowers by one and Write-back changes by what the encounter changed. A participant holds its own level while it
-- is in an encounter, copied from the sheet when a player character joins; monsters and companions hold theirs only
-- there. Both are 0 to 6, 0 meaning not exhausted.
--
-- No existing row changes beyond taking 0, and no JSON column changes: what a participant joined with and the undo
-- snapshots gain an optional exhaustionLevel in the JSON itself, and rows without it read back as they are. The
-- preview-migration maintenance job runs this file in a transaction it always rolls back, splitting it into
-- statements, so it has no DO blocks.
ALTER TABLE player_characters ADD COLUMN IF NOT EXISTS exhaustion_level INTEGER NOT NULL DEFAULT 0;
ALTER TABLE player_characters DROP CONSTRAINT IF EXISTS chk_player_characters_exhaustion_level;
ALTER TABLE player_characters ADD CONSTRAINT chk_player_characters_exhaustion_level
    CHECK (exhaustion_level BETWEEN 0 AND 6);

ALTER TABLE encounter_participants ADD COLUMN IF NOT EXISTS exhaustion_level INTEGER NOT NULL DEFAULT 0;
ALTER TABLE encounter_participants DROP CONSTRAINT IF EXISTS chk_encounter_participants_exhaustion_level;
ALTER TABLE encounter_participants ADD CONSTRAINT chk_encounter_participants_exhaustion_level
    CHECK (exhaustion_level BETWEEN 0 AND 6);
