-- D216: reaction prompts. The reactions creatures may take in answer to the newest combat command, each an
-- encounter.state.ReactionOffer (D168): who may react, to what, and the options with the command that takes each. The
-- server never waits for an answer; an offer lasts until the next combat command that isn't a reaction (a turn change
-- included), its answer or dismissal, or the undo of its command. Existing encounters start with none.
ALTER TABLE encounters ADD COLUMN IF NOT EXISTS reaction_offers JSONB NOT NULL DEFAULT '[]'::jsonb;
