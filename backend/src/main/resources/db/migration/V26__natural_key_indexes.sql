-- R7 workstream B (D169): unique natural keys on the reference tables, which the content loaders update rows by.
-- Races, classes, backgrounds, spells, feats, items, monsters and optional features are known by their name and
-- source, a subclass by its class, name and source, and a class's pool by its class, subclass and pool. A missing
-- source or subclass counts as a value (NULLS NOT DISTINCT), as content-snapshot reads the keys.
--
-- The old seeder wrote a Fighting Style once for each class that lists it, so optional_features holds 2 to 4 identical
-- copies of 9 styles. The oldest of each (by created_at, then id) is kept and the others are deleted. No foreign key
-- refers to optional features, but a feat's applied effects record the ones it chose by ID, as optionalFeaturesAdded,
-- in player_characters.level_history and dm_adjustments, so an ID of a copy there becomes the kept row's first. A
-- feature's ID appears nowhere else in those columns. The preview-migration maintenance job runs this file in a
-- transaction it always rolls back, splitting it into statements, so it has no DO blocks.

-- Each copy to delete, the row kept in its place, and a number to rewrite them one by one
CREATE TEMP TABLE optional_feature_copies ON COMMIT DROP AS
SELECT ranked.id::text AS copy_id, ranked.kept_id::text AS kept_id, row_number() OVER (ORDER BY ranked.id) AS n
FROM (SELECT id, first_value(id) OVER (PARTITION BY name, source ORDER BY created_at NULLS LAST, id) AS kept_id
      FROM optional_features) ranked
WHERE ranked.id <> ranked.kept_id;

-- The characters whose level history or DM Adjustments name a copy, with every copy's ID replaced in turn
WITH RECURSIVE rewritten AS (
    SELECT pc.id, pc.level_history::text AS history, pc.dm_adjustments::text AS adjustments, 0::bigint AS n
    FROM player_characters pc
    WHERE EXISTS (SELECT 1 FROM optional_feature_copies c
                  WHERE pc.level_history::text LIKE '%' || c.copy_id || '%'
                     OR pc.dm_adjustments::text LIKE '%' || c.copy_id || '%')
    UNION ALL
    SELECT r.id, replace(r.history, c.copy_id, c.kept_id), replace(r.adjustments, c.copy_id, c.kept_id), r.n + 1
    FROM rewritten r
    JOIN optional_feature_copies c ON c.n = r.n + 1
)
UPDATE player_characters pc
SET level_history = r.history::jsonb, dm_adjustments = r.adjustments::jsonb
FROM rewritten r
WHERE r.id = pc.id AND r.n = (SELECT count(*) FROM optional_feature_copies);

DELETE FROM optional_features o
USING optional_feature_copies c
WHERE o.id::text = c.copy_id;

CREATE UNIQUE INDEX IF NOT EXISTS uq_races_name_source ON races (name, source) NULLS NOT DISTINCT;
CREATE UNIQUE INDEX IF NOT EXISTS uq_character_classes_name_source ON character_classes (name, source) NULLS NOT DISTINCT;
CREATE UNIQUE INDEX IF NOT EXISTS uq_subclasses_class_name_source
    ON subclasses (character_class_id, name, source) NULLS NOT DISTINCT;
CREATE UNIQUE INDEX IF NOT EXISTS uq_backgrounds_name_source ON backgrounds (name, source) NULLS NOT DISTINCT;
CREATE UNIQUE INDEX IF NOT EXISTS uq_spells_name_source ON spells (name, source) NULLS NOT DISTINCT;
CREATE UNIQUE INDEX IF NOT EXISTS uq_feats_name_source ON feats (name, source) NULLS NOT DISTINCT;
CREATE UNIQUE INDEX IF NOT EXISTS uq_items_name_source ON items (name, source) NULLS NOT DISTINCT;
CREATE UNIQUE INDEX IF NOT EXISTS uq_monsters_name_source ON monsters (name, source) NULLS NOT DISTINCT;
CREATE UNIQUE INDEX IF NOT EXISTS uq_optional_features_name_source
    ON optional_features (name, source) NULLS NOT DISTINCT;
CREATE UNIQUE INDEX IF NOT EXISTS uq_class_feature_pools_class_subclass_pool
    ON class_feature_pools (class_id, subclass_id, pool_id) NULLS NOT DISTINCT;
