-- D152: every character must reference a race. This replaces the startup routine that
-- deleted all characters whenever any one of them had no race. If a race-less row exists,
-- this migration fails and the app does not start, instead of anything being deleted.
ALTER TABLE player_characters ALTER COLUMN race_id SET NOT NULL;
