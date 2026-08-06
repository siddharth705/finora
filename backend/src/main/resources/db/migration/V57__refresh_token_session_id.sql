-- A stable identity for a SESSION, as opposed to for a token.
--
-- refresh_tokens.id identifies a token, and rotation replaces the row roughly every fifteen
-- minutes, so it cannot answer "is this the device I am using right now" -- the answer would go
-- wrong the moment the access token outlived the row that minted it. session_id is generated once
-- at sign-in and copied forward by every rotation, exactly like session_started_at.
--
-- It is also the anchor the rest of the session model hangs off: idle timeout and absolute expiry
-- are already properties of the session rather than the token, and device naming, trusted devices
-- and per-session risk scoring would all key off this rather than inventing another identifier.
ALTER TABLE refresh_tokens ADD COLUMN session_id UUID;

-- Every existing token becomes its own session. That is the truthful reading: nothing recorded
-- which rotations belonged together before this column existed, so grouping them now would be
-- invention. The practical effect is limited -- only one token per session is ever unrevoked, so
-- the active-session list is unchanged; older revoked rows simply do not group.
UPDATE refresh_tokens SET session_id = id WHERE session_id IS NULL;

ALTER TABLE refresh_tokens ALTER COLUMN session_id SET NOT NULL;

-- Looked up on every authenticated request that asks "which of these sessions is mine", so it is
-- indexed rather than scanned.
CREATE INDEX idx_refresh_tokens_session_id ON refresh_tokens (session_id);

COMMENT ON COLUMN refresh_tokens.session_id IS
    'Stable identity of the sign-in session, carried across rotations. Surfaced in the access '
    'token''s sid claim so the server can tell which session is making a request.';
