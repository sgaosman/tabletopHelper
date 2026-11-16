-- Racial resource pools (M24.5b, D208). race_feature_pools links a race row to the resource pool definitions it grants,
-- as class_feature_pools links a class (V8): a Dragonborn's Breath Weapon, a Half-Orc's Relentless Endurance, an
-- Aasimar's Healing Hands. A subrace is a race row of its own, so each row with the trait has its own mapping; the pool
-- comes at min_level, the character's total level. The resource-pools content loader writes the rows; a race row that
-- goes takes its mappings with it.
--
-- No existing row changes and no JSON column changes. The preview-migration maintenance job runs this file in a
-- transaction it always rolls back, splitting it into statements, so it has no DO blocks.
CREATE TABLE IF NOT EXISTS race_feature_pools (
    id UUID PRIMARY KEY,
    race_id UUID NOT NULL REFERENCES races(id) ON DELETE CASCADE,
    min_level INTEGER NOT NULL,
    pool_id VARCHAR(64) NOT NULL REFERENCES resource_pool_definitions(pool_id),
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- The natural key the content loader upserts by, which also indexes race_id, its first column
CREATE UNIQUE INDEX IF NOT EXISTS uq_race_feature_pools_race_pool ON race_feature_pools (race_id, pool_id);
