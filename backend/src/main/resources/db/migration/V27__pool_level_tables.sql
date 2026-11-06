-- R7 workstream D (D169, D172): a pool definition's level table, the maximum, rest and die that change with the
-- level of one class, such as Rage's uses or Bardic Inspiration's die and its short rest from Bard 5. It is
-- {levelOf, rows: [{level, maxUses, resetOn, die, unlimited}]}: levelOf names the class whose level picks the row,
-- each row applies from its level until the next, and a field a row leaves out is the definition's own. NULL for a
-- pool that doesn't change with level. The resource-pools content loader fills it.
ALTER TABLE resource_pool_definitions ADD COLUMN IF NOT EXISTS level_table JSONB;
