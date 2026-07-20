ALTER TABLE spells ADD COLUMN IF NOT EXISTS effect_template jsonb;

ALTER TABLE encounter_participants ADD COLUMN IF NOT EXISTS spell_attack_bonus INTEGER;
ALTER TABLE encounter_participants ADD COLUMN IF NOT EXISTS spell_save_dc INTEGER;
ALTER TABLE encounter_participants ADD COLUMN IF NOT EXISTS spellcasting_ability VARCHAR(20);
ALTER TABLE encounter_participants ADD COLUMN IF NOT EXISTS spells_known jsonb;
