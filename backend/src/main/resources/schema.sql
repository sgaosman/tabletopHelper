CREATE INDEX IF NOT EXISTS idx_spells_classes_gin ON spells USING GIN (classes);
