-- Track non-concentration persistent spells (e.g., Spiritual Weapon)
ALTER TABLE encounter_participants ADD COLUMN active_spell VARCHAR(200);
ALTER TABLE encounter_participants ADD COLUMN active_spell_slot_level INTEGER;

-- Force re-seed of spell effect templates for new repeatEffect definitions
UPDATE spells SET effect_template = NULL WHERE effect_template IS NOT NULL;
