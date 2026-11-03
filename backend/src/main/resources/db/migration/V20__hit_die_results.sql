-- R6 workstream C (D167): the hit point maximum is now worked out from each level's Hit Die Result, so every level
-- history entry records one. The levels recorded before get the values calculateHpGain used for them: the class's
-- Hit Die at character level 1, and half the die plus 1 after that. The die is the class's that the entry names, by
-- its ID, or else the character's Hit Dice of that class; a character created without a class had d8 levels. An
-- entry that already has a result is left alone. The preview-migration maintenance job runs this file in a
-- transaction it always rolls back, splitting it into statements, so it has no DO blocks.
UPDATE player_characters p
SET level_history = converted.history
FROM (
    SELECT pc.id,
           (SELECT jsonb_agg(
                       CASE
                           WHEN jsonb_typeof(e.value) <> 'object'
                               OR jsonb_typeof(e.value -> 'hitDieResult') = 'number'
                               THEN e.value
                           ELSE e.value || jsonb_build_object('hitDieResult',
                               CASE
                                   WHEN COALESCE(CASE WHEN jsonb_typeof(e.value -> 'characterLevel') = 'number'
                                                      THEN (e.value ->> 'characterLevel')::numeric::int END,
                                                 e.position::int) = 1
                                       THEN die.size
                                   ELSE die.size / 2 + 1
                               END)
                       END ORDER BY e.position)
            FROM jsonb_array_elements(pc.level_history) WITH ORDINALITY AS e(value, position)
            CROSS JOIN LATERAL (
                SELECT COALESCE(
                           (SELECT cc.hit_dice FROM character_classes cc
                            WHERE cc.id::text = lower(e.value ->> 'classId')),
                           CASE WHEN jsonb_typeof(pc.hit_dice -> (e.value ->> 'className') -> 'dieSize') = 'number'
                                     AND (pc.hit_dice -> (e.value ->> 'className') ->> 'dieSize')::numeric > 0
                                THEN (pc.hit_dice -> (e.value ->> 'className') ->> 'dieSize')::numeric::int END,
                           8) AS size
            ) AS die) AS history
    FROM player_characters pc
    WHERE jsonb_typeof(pc.level_history) = 'array'
      AND jsonb_array_length(pc.level_history) > 0
) AS converted
WHERE p.id = converted.id
  AND p.level_history IS DISTINCT FROM converted.history;
