-- Item use in combat (D210). Items keep what the 5etools data says about charges and spells, which the loader dropped
-- before: how many charges an item has, when it regains them ("dawn", "dusk", ...), how many it regains as a dice
-- formula with the markup taken off ("1d6 + 1"), the spells it casts as the data lists them (a JSON object such as
-- {"charges": {"1": ["magic missile"]}}), a spell scroll's level, and the save DC and attack bonus the item's own text
-- gives its spells ("save DC 15"); null where the item says none. ItemLoader's version rises to 3, so the next content
-- load writes every item again.
--
-- A participant records the items it has used up, such as a potion drunk or a scroll read, in items_used, so undo puts
-- one back and Write-back takes it off the sheet at the end of the encounter. Every existing participant takes [].
--
-- No existing row changes beyond taking the defaults. The preview-migration maintenance job runs this file in a
-- transaction it always rolls back, splitting it into statements, so it has no DO blocks.
ALTER TABLE items ADD COLUMN IF NOT EXISTS charges INTEGER;
ALTER TABLE items ADD COLUMN IF NOT EXISTS recharge VARCHAR(20);
ALTER TABLE items ADD COLUMN IF NOT EXISTS recharge_amount VARCHAR(30);
ALTER TABLE items ADD COLUMN IF NOT EXISTS attached_spells JSONB;
ALTER TABLE items ADD COLUMN IF NOT EXISTS spell_scroll_level INTEGER;
ALTER TABLE items ADD COLUMN IF NOT EXISTS spell_save_dc INTEGER;
ALTER TABLE items ADD COLUMN IF NOT EXISTS spell_attack_bonus INTEGER;

ALTER TABLE encounter_participants ADD COLUMN IF NOT EXISTS items_used JSONB NOT NULL DEFAULT '[]'::jsonb;
