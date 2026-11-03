-- R6 workstream D (D158): every command that changes a character now locks its row first, and this version is the
-- backstop behind the lock. Each write checks it and raises it by one, so a write from a copy of the row that someone
-- else has changed since fails, answering 409, instead of overwriting the newer row. Existing characters start at 0.
-- The preview-migration maintenance job runs this file in a transaction it always rolls back, splitting it into
-- statements, so it has no DO blocks.
ALTER TABLE player_characters ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
