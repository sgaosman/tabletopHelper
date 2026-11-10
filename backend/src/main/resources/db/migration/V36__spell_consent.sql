-- Spell consent (D198). A trait such as the rakshasa's Limited Magic Immunity keeps spells of 6th level or lower off
-- the creature "unless it wishes to be" affected (MM p.257). Whoever controls the creature, the DM or the player a spell
-- gave control to, chooses which spells it lets through: NEVER, HELPFUL (spells that don't harm it, such as Heal and
-- Bless) or ALWAYS. Every participant takes HELPFUL; it is read only for a creature with such a trait.
--
-- No existing row changes beyond taking the default, and no JSON column changes. The preview-migration maintenance job
-- runs this file in a transaction it always rolls back, splitting it into statements, so it has no DO blocks.
ALTER TABLE encounter_participants ADD COLUMN IF NOT EXISTS spell_consent VARCHAR(10) NOT NULL DEFAULT 'HELPFUL';
ALTER TABLE encounter_participants DROP CONSTRAINT IF EXISTS chk_encounter_participants_spell_consent;
ALTER TABLE encounter_participants ADD CONSTRAINT chk_encounter_participants_spell_consent
    CHECK (spell_consent IN ('NEVER', 'HELPFUL', 'ALWAYS'));
