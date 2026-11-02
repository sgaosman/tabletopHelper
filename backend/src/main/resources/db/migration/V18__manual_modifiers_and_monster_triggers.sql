-- R5 workstream F (D164, D165): the Manual Modifiers setting, and the monster pool triggers. The preview-migration
-- maintenance job runs this file in a transaction it always rolls back, splitting it into statements, so it has no
-- DO blocks.

-- ── Manual Modifiers (D164) ────────────────────────────────────────────────────────────────────────────────
-- Numbers entered by hand in combat in place of the server's: an attack bonus, damage dice, a save DC or a forced
-- critical hit. The DM may always enter them, and players while this is on, as it is until the DM turns it off.
ALTER TABLE campaigns ADD COLUMN IF NOT EXISTS allow_player_manual_modifiers BOOLEAN NOT NULL DEFAULT TRUE;

-- ── Monster pool triggers (D165) ───────────────────────────────────────────────────────────────────────────
-- Legendary Resistance follows the trait of that name, not legendary actions, so the monsters with legendary
-- actions but no Legendary Resistance, such as the Aboleth, stop getting the pool. Monsters already in an
-- encounter keep the pools they joined with. ResourcePoolSeeder seeds the same rows into an empty database.
UPDATE monster_pool_triggers
SET trigger_condition = '{"hasFeature": "Legendary Resistance"}'::jsonb
WHERE pool_id = 'monster:legendary-resistance'
  AND trigger_condition = '{"hasField": "legendary"}'::jsonb;

-- Each recharge action already gets its own pool from the monster's action templates, which this generic breath
-- weapon trigger duplicated. Its pool definition stays, unused.
DELETE FROM monster_pool_triggers
WHERE pool_id = 'monster:breath-weapon-5-6'
  AND trigger_condition = '{"hasRecharge": true}'::jsonb;
