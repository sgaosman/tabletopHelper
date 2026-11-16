-- D222: the uses a level-down took away. Removing a level removes the pools, slot levels and Hit Dice it gave, or
-- lowers their maximum, and a pool or slot that comes back would otherwise come back unspent. Each entry is a
-- character.state.LevelDownUses: the character level removed and how many pool uses, slots and Hit Dice it took away
-- spent. Gaining that character level again spends them again; a long rest drops every entry, and a short rest the
-- short-rest pools' and pact slots' counts. Existing characters start with none.
ALTER TABLE player_characters ADD COLUMN IF NOT EXISTS level_down_uses JSONB NOT NULL DEFAULT '[]'::jsonb;
