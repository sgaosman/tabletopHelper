-- D156: rotating refresh tokens. The token itself is a random value held in an httpOnly cookie;
-- only its SHA-256 hash (hex) is stored. Each login starts a family, each refresh replaces the
-- token (replaced_by_id), and presenting a replaced token revokes the whole family.
CREATE TABLE IF NOT EXISTS refresh_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL CONSTRAINT fk_refresh_tokens_user REFERENCES users(id),
    family_id UUID NOT NULL,
    token_hash VARCHAR(64) NOT NULL CONSTRAINT uq_refresh_tokens_token_hash UNIQUE,
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    replaced_by_id UUID,
    revoked_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_rt_user_id ON refresh_tokens(user_id);
CREATE INDEX IF NOT EXISTS idx_rt_family_id ON refresh_tokens(family_id);
