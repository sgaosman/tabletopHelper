-- R5 workstream C (D168): the encounter JSON columns now map to typed records, so every row is
-- normalised to the one shape its record reads. The preview-migration maintenance job runs this file
-- in a transaction it always rolls back, splitting it into statements, so it has no DO blocks.

-- A value of the wrong JSON type, which the old code read as nothing, would now stop its row loading
UPDATE encounter_participants SET active_conditions = NULL WHERE jsonb_typeof(active_conditions) <> 'array';
UPDATE encounter_participants SET spell_slots_current = NULL WHERE jsonb_typeof(spell_slots_current) <> 'object';
UPDATE encounter_participants SET spells_known = NULL WHERE jsonb_typeof(spells_known) <> 'array';
UPDATE encounter_participants SET resource_pools_current = NULL WHERE jsonb_typeof(resource_pools_current) <> 'array';
UPDATE encounters SET monsters_in_lair = NULL WHERE jsonb_typeof(monsters_in_lair) <> 'array';

-- Spell slots: {"max": M, "remaining": R} becomes {"max": M, "used": M - R}, with the used count kept
-- between 0 and M. Only the keys "1" to "9" and "pact_1" to "pact_9" are kept, so the
-- {"innate": true, "source": "innate", "dailySpellCount": N} object of an innate caster becomes NULL:
-- its daily uses are resource pools already (D165). Rows already in the new shape are left alone.
UPDATE encounter_participants p
SET spell_slots_current = converted.slots
FROM (
    SELECT ep.id,
           (SELECT jsonb_object_agg(slot.key, jsonb_build_object(
                       'max', (slot.value ->> 'max')::numeric::int,
                       'used', LEAST(GREATEST(
                           CASE
                               WHEN jsonb_typeof(slot.value -> 'used') = 'number'
                                   THEN (slot.value ->> 'used')::numeric::int
                               WHEN jsonb_typeof(slot.value -> 'remaining') = 'number'
                                   THEN (slot.value ->> 'max')::numeric::int - (slot.value ->> 'remaining')::numeric::int
                               ELSE 0
                           END, 0), (slot.value ->> 'max')::numeric::int)))
            FROM jsonb_each(ep.spell_slots_current) AS slot
            WHERE slot.key ~ '^(pact_)?[1-9]$'
              AND jsonb_typeof(slot.value) = 'object'
              AND jsonb_typeof(slot.value -> 'max') = 'number') AS slots
    FROM encounter_participants ep
    WHERE jsonb_typeof(ep.spell_slots_current) = 'object'
      AND ep.spell_slots_current <> '{}'::jsonb
) AS converted
WHERE p.id = converted.id
  AND p.spell_slots_current IS DISTINCT FROM converted.slots;

-- Conditions stored as names, such as ["prone"], become entries with the defaults the server gave them
-- when it read them: no duration, applied in round 1 and no source. An element that is neither a name
-- nor an entry is dropped.
UPDATE encounter_participants p
SET active_conditions = converted.conditions
FROM (
    SELECT ep.id,
           (SELECT COALESCE(jsonb_agg(
                       CASE jsonb_typeof(c.value)
                           WHEN 'string' THEN jsonb_build_object(
                               'name', c.value #>> '{}',
                               'duration', NULL,
                               'appliedRound', 1,
                               'sourceSpellName', NULL,
                               'sourceParticipantId', NULL,
                               'sourceRequiresConcentration', NULL)
                           ELSE c.value
                       END ORDER BY c.position), '[]'::jsonb)
            FROM jsonb_array_elements(ep.active_conditions) WITH ORDINALITY AS c(value, position)
            WHERE jsonb_typeof(c.value) IN ('string', 'object')) AS conditions
    FROM encounter_participants ep
    WHERE jsonb_typeof(ep.active_conditions) = 'array'
) AS converted
WHERE p.id = converted.id
  AND p.active_conditions IS DISTINCT FROM converted.conditions;
