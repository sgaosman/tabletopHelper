-- R7 workstream G (D150, D168): drops the seven player_characters columns nothing has read or written since R6.
-- hit_dice_total, hit_dice_remaining and hit_dice_map are replaced by hit_dice, which V19 filled from hit_dice_map;
-- prepared_spells, attuned_items and equipped_items were never read, and the equipment entries carry what they held;
-- feat_resources was replaced by the feat:<feat> pools in resource_pools (D158). Destructive: back up the database
-- and dry-run it with preview-migration first, which lists each value a row loses.
ALTER TABLE player_characters DROP COLUMN IF EXISTS hit_dice_total;
ALTER TABLE player_characters DROP COLUMN IF EXISTS hit_dice_remaining;
ALTER TABLE player_characters DROP COLUMN IF EXISTS hit_dice_map;
ALTER TABLE player_characters DROP COLUMN IF EXISTS feat_resources;
ALTER TABLE player_characters DROP COLUMN IF EXISTS prepared_spells;
ALTER TABLE player_characters DROP COLUMN IF EXISTS attuned_items;
ALTER TABLE player_characters DROP COLUMN IF EXISTS equipped_items;
