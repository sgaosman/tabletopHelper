-- R7 workstream E (D167, D169): the mechanical effects of a race or subclass that the 5etools data doesn't give, so
-- the character rules read them from the data instead of matching names. Each is an object such as
-- {"hpPerLevel": 1}, the Hill Dwarf's Dwarven Toughness, a bonus for every character level, or
-- {"hpPerClassLevel": 1}, the Draconic Bloodline's Draconic Resilience, a bonus for every level in the subclass's
-- class. NULL for none. The races and classes content loaders fill them from data/content/race-effects.json and
-- subclass-effects.json.
ALTER TABLE races ADD COLUMN IF NOT EXISTS effects JSONB;
ALTER TABLE subclasses ADD COLUMN IF NOT EXISTS effects JSONB;
