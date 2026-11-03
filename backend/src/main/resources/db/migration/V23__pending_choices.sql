-- R6 workstream F (D159): Pending Choices, the choices a character's levels owe that its player hasn't made yet: an
-- Ability Score Improvement or a feat in its place, a subclass, Expertise, a Fighting Style, cantrips or spells, and a
-- feat its race or background gives at creation. Each entry is a character.state.PendingChoice, oldest level first.
-- Making one records the answer in its level's history entry and takes it off the list, and levelling down removes
-- the level's own. Existing characters start with none; the backfill-pending-choices maintenance job adds the Ability
-- Score Improvements, subclasses and Expertise their levels missed. The preview-migration maintenance job runs this
-- file in a transaction it always rolls back, splitting it into statements, so it has no DO blocks.
ALTER TABLE player_characters ADD COLUMN IF NOT EXISTS pending_choices JSONB NOT NULL DEFAULT '[]'::jsonb;
