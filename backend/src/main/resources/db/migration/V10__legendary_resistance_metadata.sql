-- Legendary resistance undo metadata on combat log entries
ALTER TABLE combat_logs ADD COLUMN IF NOT EXISTS legendary_resistance_eligible BOOLEAN DEFAULT FALSE;
ALTER TABLE combat_logs ADD COLUMN IF NOT EXISTS lr_target_id UUID;
ALTER TABLE combat_logs ADD COLUMN IF NOT EXISTS lr_damage_dealt INTEGER;
ALTER TABLE combat_logs ADD COLUMN IF NOT EXISTS lr_conditions_applied VARCHAR(500);
ALTER TABLE combat_logs ADD COLUMN IF NOT EXISTS lr_resolved BOOLEAN DEFAULT FALSE;
