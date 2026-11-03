-- R6 workstream B (D168): the player character JSON columns now map to typed records (character.state), so every
-- row is normalised to the one shape its record reads. The preview-migration maintenance job runs this file in a
-- transaction it always rolls back, splitting it into statements, so it has no DO blocks.

-- A value of the wrong JSON type, which the old code read as nothing, would now stop its row loading
UPDATE player_characters SET saving_throw_proficiencies = NULL WHERE jsonb_typeof(saving_throw_proficiencies) <> 'array';
UPDATE player_characters SET skill_proficiencies = NULL WHERE jsonb_typeof(skill_proficiencies) <> 'array';
UPDATE player_characters SET skill_expertises = NULL WHERE jsonb_typeof(skill_expertises) <> 'array';
UPDATE player_characters SET armor_proficiencies = NULL WHERE jsonb_typeof(armor_proficiencies) <> 'array';
UPDATE player_characters SET weapon_proficiencies = NULL WHERE jsonb_typeof(weapon_proficiencies) <> 'array';
UPDATE player_characters SET tool_proficiencies = NULL WHERE jsonb_typeof(tool_proficiencies) <> 'array';
UPDATE player_characters SET language_proficiencies = NULL WHERE jsonb_typeof(language_proficiencies) <> 'array';
UPDATE player_characters SET damage_resistances = NULL WHERE jsonb_typeof(damage_resistances) <> 'array';
UPDATE player_characters SET damage_immunities = NULL WHERE jsonb_typeof(damage_immunities) <> 'array';
UPDATE player_characters SET condition_immunities = NULL WHERE jsonb_typeof(condition_immunities) <> 'array';
UPDATE player_characters SET features = NULL WHERE jsonb_typeof(features) <> 'array';
UPDATE player_characters SET spells_known = NULL WHERE jsonb_typeof(spells_known) <> 'array';
UPDATE player_characters SET spell_slots = NULL WHERE jsonb_typeof(spell_slots) <> 'object';
UPDATE player_characters SET equipment = NULL WHERE jsonb_typeof(equipment) <> 'array';
UPDATE player_characters SET currency = NULL WHERE jsonb_typeof(currency) <> 'object';
UPDATE player_characters SET racial_ability_bonuses = NULL WHERE jsonb_typeof(racial_ability_bonuses) <> 'array';
UPDATE player_characters SET multiclass_entries = NULL WHERE jsonb_typeof(multiclass_entries) <> 'array';
UPDATE player_characters SET level_history = NULL WHERE jsonb_typeof(level_history) <> 'array';
UPDATE player_characters SET feat_resources = NULL WHERE jsonb_typeof(feat_resources) <> 'array';
UPDATE player_characters SET resource_pools = NULL WHERE jsonb_typeof(resource_pools) <> 'array';

-- Spell slots: {"total": T, "used": U} becomes {"max": T, "used": U}, the shape participants have had since V15,
-- with the used count kept between 0 and the maximum. Only the keys "1" to "9" and "pact_1" to "pact_9" holding an
-- object with a numeric maximum are kept. Rows already in the new shape are left alone.
UPDATE player_characters p
SET spell_slots = converted.slots
FROM (
    SELECT pc.id,
           (SELECT jsonb_object_agg(slot.key, jsonb_build_object(
                       'max', slot.max,
                       'used', LEAST(GREATEST(slot.used, 0), slot.max)))
            FROM (SELECT s.key,
                         CASE
                             WHEN jsonb_typeof(s.value -> 'max') = 'number' THEN (s.value ->> 'max')::numeric::int
                             ELSE (s.value ->> 'total')::numeric::int
                         END AS max,
                         CASE
                             WHEN jsonb_typeof(s.value -> 'used') = 'number' THEN (s.value ->> 'used')::numeric::int
                             ELSE 0
                         END AS used
                  FROM jsonb_each(pc.spell_slots) AS s
                  WHERE s.key ~ '^(pact_)?[1-9]$'
                    AND jsonb_typeof(s.value) = 'object'
                    AND (jsonb_typeof(s.value -> 'max') = 'number' OR jsonb_typeof(s.value -> 'total') = 'number')
                 ) AS slot) AS slots
    FROM player_characters pc
    WHERE jsonb_typeof(pc.spell_slots) = 'object'
      AND pc.spell_slots <> '{}'::jsonb
) AS converted
WHERE p.id = converted.id
  AND p.spell_slots IS DISTINCT FROM converted.slots;

-- Hit Dice move from the deprecated hit_dice_map to a column of their own (D158), still keyed by class name:
-- {"Fighter": {"total": 5, "remaining": 3, "faces": 10}} becomes {"Fighter": {"dieSize": 10, "total": 5,
-- "remaining": 3}}. A count the old code would have read as 0, being missing or not a number, stays 0, and an entry
-- that isn't an object is left out. hit_dice_map, hit_dice_total and hit_dice_remaining stay until R7 drops them.
ALTER TABLE player_characters ADD COLUMN IF NOT EXISTS hit_dice JSONB;

UPDATE player_characters p
SET hit_dice = converted.dice
FROM (
    SELECT pc.id,
           (SELECT COALESCE(jsonb_object_agg(d.key, jsonb_build_object(
                       'dieSize', CASE WHEN jsonb_typeof(d.value -> 'faces') = 'number'
                                       THEN (d.value ->> 'faces')::numeric::int ELSE 0 END,
                       'total', CASE WHEN jsonb_typeof(d.value -> 'total') = 'number'
                                     THEN (d.value ->> 'total')::numeric::int ELSE 0 END,
                       'remaining', CASE WHEN jsonb_typeof(d.value -> 'remaining') = 'number'
                                         THEN (d.value ->> 'remaining')::numeric::int ELSE 0 END)), '{}'::jsonb)
            FROM jsonb_each(pc.hit_dice_map) AS d
            WHERE jsonb_typeof(d.value) = 'object') AS dice
    FROM player_characters pc
    WHERE jsonb_typeof(pc.hit_dice_map) = 'object'
) AS converted
WHERE p.id = converted.id
  AND p.hit_dice IS NULL;

-- Equipment stored as names, such as ["Longsword"], becomes entries with just a name, as the weapon code read
-- them. An element that is neither a name nor an entry is dropped.
UPDATE player_characters p
SET equipment = converted.items
FROM (
    SELECT pc.id,
           (SELECT COALESCE(jsonb_agg(
                       CASE jsonb_typeof(e.value)
                           WHEN 'string' THEN jsonb_build_object('name', e.value #>> '{}')
                           ELSE e.value
                       END ORDER BY e.position), '[]'::jsonb)
            FROM jsonb_array_elements(pc.equipment) WITH ORDINALITY AS e(value, position)
            WHERE jsonb_typeof(e.value) IN ('string', 'object')) AS items
    FROM player_characters pc
    WHERE jsonb_typeof(pc.equipment) = 'array'
) AS converted
WHERE p.id = converted.id
  AND p.equipment IS DISTINCT FROM converted.items;
