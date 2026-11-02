-- D157: the campaign setting "show exact monster HP". While it is off, the Player View shows each
-- monster's HP as a Health Status (Unharmed, Injured, Bloodied, Down) instead of numbers.
ALTER TABLE campaigns ADD COLUMN IF NOT EXISTS show_exact_monster_hp BOOLEAN NOT NULL DEFAULT FALSE;
