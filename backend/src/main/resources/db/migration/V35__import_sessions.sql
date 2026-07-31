-- Persisted staged-review state (ADR-0002 / docs/adr/0002-persisted-import-sessions.md). See
-- ImportSession.java's own doc comment for the full reasoning -- this is a transient staging
-- artifact, not permanent financial data, hence JSON text columns for the staged rows/detected
-- account rather than new normalized tables.
CREATE TABLE import_sessions (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    file_name              VARCHAR(255) NOT NULL,
    file_content           BYTEA NOT NULL,
    staged_rows_json       TEXT NOT NULL,
    detected_account_json  TEXT NOT NULL,
    status                 VARCHAR(20) NOT NULL DEFAULT 'STAGED',
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at             TIMESTAMPTZ NOT NULL,
    confirmed_at           TIMESTAMPTZ
);

-- Backs both real query patterns: "this user's active sessions" (resume list) and the
-- opportunistic expiry cleanup that runs on their next stage() call (see ImportSessionService --
-- no @Scheduled cleanup job exists, this codebase has no background job infrastructure yet).
CREATE INDEX idx_import_sessions_user_status ON import_sessions(user_id, status);
