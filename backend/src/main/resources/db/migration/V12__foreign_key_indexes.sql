-- D170: indexes on the foreign keys that lookups and joins filter on. All indexes now live in
-- migrations; the JPA @Index annotations are gone. IF NOT EXISTS because databases Hibernate
-- built before D097 may already have the first five under these names.
CREATE INDEX IF NOT EXISTS idx_pc_user_id ON player_characters(user_id);
CREATE INDEX IF NOT EXISTS idx_pc_campaign_id ON player_characters(campaign_id);
CREATE INDEX IF NOT EXISTS idx_ep_encounter_id ON encounter_participants(encounter_id);
CREATE INDEX IF NOT EXISTS idx_ep_character_id ON encounter_participants(character_id);
CREATE INDEX IF NOT EXISTS idx_cl_encounter_id ON combat_logs(encounter_id);
CREATE INDEX IF NOT EXISTS idx_enc_campaign_id ON encounters(campaign_id);
CREATE INDEX IF NOT EXISTS idx_cm_user_id ON campaign_members(user_id);
