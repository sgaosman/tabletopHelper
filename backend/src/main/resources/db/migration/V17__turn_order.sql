-- R5 workstream E (D157, D162, D163): turns tracked by whose they are. The preview-migration maintenance job
-- runs this file in a transaction it always rolls back, splitting it into statements, so it has no DO blocks.

-- ── The current turn (D163) ────────────────────────────────────────────────────────────────────────────────
-- The turn order is computed from initiative, DEX and the DM's order among ties, with the Lair's turn while a
-- monster in its lair is alive, so a stored place in it drifts. The encounter remembers the participant whose
-- turn it is, or that it is the Lair's, and never both.
ALTER TABLE encounters ADD COLUMN IF NOT EXISTS current_turn_participant_id UUID;
ALTER TABLE encounters ADD COLUMN IF NOT EXISTS is_lair_turn BOOLEAN NOT NULL DEFAULT FALSE;

-- The participant flagged as having the turn; with more than one flagged, the first in the order
UPDATE encounters e
SET current_turn_participant_id = (
        SELECT p.id
        FROM encounter_participants p
        WHERE p.encounter_id = e.id
          AND p.is_current_turn
        ORDER BY p.sort_order NULLS LAST, p.id
        LIMIT 1)
WHERE e.current_turn_participant_id IS NULL
  AND EXISTS (SELECT 1 FROM encounter_participants p WHERE p.encounter_id = e.id AND p.is_current_turn);

-- A started encounter with no participant flagged keeps the one at its turn index, counted in sort order with
-- unsorted participants last, as the old code counted it
UPDATE encounters e
SET current_turn_participant_id = (
        SELECT placed.id
        FROM (SELECT p.id, row_number() OVER (ORDER BY p.sort_order NULLS LAST, p.id) - 1 AS place
              FROM encounter_participants p
              WHERE p.encounter_id = e.id) AS placed
        WHERE placed.place = GREATEST(COALESCE(e.current_turn_index, 0), 0))
WHERE e.current_turn_participant_id IS NULL
  AND e.status <> 'PREPARING'
  AND EXISTS (SELECT 1 FROM encounter_participants p WHERE p.encounter_id = e.id);

-- Removing a participant clears the marker if the app hasn't moved it on already, and deleting an encounter,
-- which deletes its participants first, still works
ALTER TABLE encounters DROP CONSTRAINT IF EXISTS fk_encounters_current_turn_participant;
ALTER TABLE encounters ADD CONSTRAINT fk_encounters_current_turn_participant
    FOREIGN KEY (current_turn_participant_id) REFERENCES encounter_participants (id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_enc_current_turn_participant_id ON encounters (current_turn_participant_id);

ALTER TABLE encounters DROP CONSTRAINT IF EXISTS encounters_one_current_turn_check;
ALTER TABLE encounters ADD CONSTRAINT encounters_one_current_turn_check
    CHECK (NOT (is_lair_turn AND current_turn_participant_id IS NOT NULL));

ALTER TABLE encounter_participants DROP COLUMN IF EXISTS is_current_turn;
ALTER TABLE encounters DROP COLUMN IF EXISTS current_turn_index;

-- ── Death saves due (D163) ─────────────────────────────────────────────────────────────────────────────────
-- Set when a Dying PC's turn starts, until it rolls the death save. A PC already dying is prompted from the
-- start of its next turn.
ALTER TABLE encounter_participants ADD COLUMN IF NOT EXISTS death_save_due BOOLEAN NOT NULL DEFAULT FALSE;

-- ── Repeat Saves (D162) ────────────────────────────────────────────────────────────────────────────────────
-- Off: the server rolls player characters' Repeat Saves, as it does monsters'. On: their players roll them.
ALTER TABLE campaigns ADD COLUMN IF NOT EXISTS prompt_player_repeat_saves BOOLEAN NOT NULL DEFAULT FALSE;
