ALTER TABLE encounter_participants ADD COLUMN concentration_slot_level INTEGER;

-- Clear effect templates to force re-seed with updated spell data (repeatEffect, requiresManualResolution changes)
UPDATE spells SET effect_template = NULL WHERE effect_template IS NOT NULL;
