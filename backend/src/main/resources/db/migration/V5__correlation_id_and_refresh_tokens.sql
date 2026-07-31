-- Ties every audit entry back to the request that caused it, so a support/security
-- investigation can go from "this HTTP request logged an error" straight to "here's
-- every audit-worthy action that request triggered."
ALTER TABLE audit_logs ADD COLUMN request_id VARCHAR(64);
CREATE INDEX idx_audit_logs_request_id ON audit_logs(request_id);

-- Refresh tokens for the access/refresh token pair (Phase 1.5 hardening). Stored hashed,
-- same pattern as password_reset_tokens — the raw token only ever exists in the response
-- body and the client's storage, never at rest server-side.
CREATE TABLE refresh_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  VARCHAR(64) NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_refresh_token_hash ON refresh_tokens(token_hash);
CREATE INDEX idx_refresh_token_user ON refresh_tokens(user_id) WHERE revoked_at IS NULL;
