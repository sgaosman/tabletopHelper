-- R6 workstream G (D166): Write-back. When the DM completes an encounter and applies its results, or removes a player
-- character from one under way, what the encounter changed for the character is applied to its sheet as the sheet then
-- stands, so two encounters' changes add up. Each player participant keeps what it joined with in joined_with, a
-- character.state.CombatTotals: its hit points, its temporary hit points, each spell slot's used count and each pool's
-- current uses. A participant joins with its sheet when added, and again when the encounter starts. Encounters already
-- under way didn't keep what their participants joined with, so those join with their sheets as they stand now; a
-- Preparing encounter's are copied when it starts, and a Completed one's keep none. results_applied records the DM's
-- choice: null until an encounter completes, and for those completed before R6. is_dead marks a character who died,
-- until the DM revives it. The preview-migration maintenance job runs this file in a transaction it always rolls back,
-- splitting it into statements, so it has no DO blocks.
ALTER TABLE encounter_participants ADD COLUMN IF NOT EXISTS joined_with JSONB;
ALTER TABLE encounters ADD COLUMN IF NOT EXISTS results_applied BOOLEAN;
ALTER TABLE player_characters ADD COLUMN IF NOT EXISTS is_dead BOOLEAN NOT NULL DEFAULT FALSE;

-- What the player participants of Active and Paused encounters join with: their sheets' hit points, kept between 0 and
-- the maximum, temporary hit points, the used count of each slot keyed "1" to "9" or "pact_1" to "pact_9", kept between
-- 0 and its maximum, and the current uses of each pool, the first of two with one ID counting, as a participant copies
-- them on joining
UPDATE encounter_participants p
SET joined_with = jsonb_build_object(
        'hpCurrent', LEAST(GREATEST(pc.hp_current, 0), GREATEST(pc.hp_max, 1)),
        'hpTemp', GREATEST(COALESCE(pc.hp_temp, 0), 0),
        'slotsUsed', COALESCE((
            SELECT jsonb_object_agg(slot.key,
                       LEAST(GREATEST((slot.value ->> 'used')::numeric::int, 0), (slot.value ->> 'max')::numeric::int))
            FROM jsonb_each(CASE WHEN jsonb_typeof(pc.spell_slots) = 'object' THEN pc.spell_slots END) AS slot
            WHERE slot.key ~ '^(pact_)?[1-9]$'
              AND jsonb_typeof(slot.value -> 'max') = 'number'
              AND jsonb_typeof(slot.value -> 'used') = 'number'), '{}'::jsonb),
        'poolsCurrentUses', COALESCE((
            SELECT jsonb_object_agg(firsts.pool_id, firsts.current_uses)
            FROM (SELECT DISTINCT ON (pool.value ->> 'poolId')
                         pool.value ->> 'poolId' AS pool_id,
                         (pool.value ->> 'currentUses')::numeric::int AS current_uses
                  FROM jsonb_array_elements(CASE WHEN jsonb_typeof(pc.resource_pools) = 'array'
                                                 THEN pc.resource_pools END) WITH ORDINALITY AS pool(value, position)
                  WHERE jsonb_typeof(pool.value -> 'poolId') = 'string'
                    AND jsonb_typeof(pool.value -> 'currentUses') = 'number'
                  ORDER BY pool.value ->> 'poolId', pool.position) AS firsts), '{}'::jsonb))
FROM encounters e, player_characters pc
WHERE e.id = p.encounter_id
  AND pc.id = p.character_id
  AND p.participant_type = 'PLAYER'
  AND e.status IN ('ACTIVE', 'PAUSED')
  AND p.joined_with IS NULL;
