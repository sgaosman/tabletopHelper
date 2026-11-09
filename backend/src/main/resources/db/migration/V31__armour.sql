-- M13.5: armour and Armour Class. Items gain the armour columns the AC derivation reads: the category (LIGHT, MEDIUM,
-- HEAVY or SHIELD; null for anything else), the base AC (a shield's 2), the Strength a heavy armour needs, whether it
-- gives disadvantage on Dexterity (Stealth) checks, and a magic armour's or shield's bonus to AC. ItemLoader fills
-- them: its version rises to 2, so the next content load writes every item again. player_characters.armour_class keeps
-- its meaning, the AC before the DM's adjustments, but is now derived from the equipped armour on every command.
ALTER TABLE items ADD COLUMN IF NOT EXISTS armour_category VARCHAR(10);
ALTER TABLE items ADD COLUMN IF NOT EXISTS armour_class_base INT;
ALTER TABLE items ADD COLUMN IF NOT EXISTS strength_requirement INT;
ALTER TABLE items ADD COLUMN IF NOT EXISTS stealth_disadvantage BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE items ADD COLUMN IF NOT EXISTS bonus_ac INT;

ALTER TABLE items DROP CONSTRAINT IF EXISTS items_armour_category_check;
ALTER TABLE items ADD CONSTRAINT items_armour_category_check
    CHECK (armour_category IS NULL OR armour_category IN ('LIGHT', 'MEDIUM', 'HEAVY', 'SHIELD'));
