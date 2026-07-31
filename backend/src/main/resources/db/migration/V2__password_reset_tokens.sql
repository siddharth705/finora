-- Password reset tokens. Tokens are stored hashed (SHA-256) so a DB leak alone
-- doesn't hand out working reset links; the raw token only ever exists in the
-- API response and the (would-be) email link.
CREATE TABLE password_reset_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  VARCHAR(64) NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_reset_token_hash ON password_reset_tokens(token_hash);
