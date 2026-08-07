-- The index behind the per-request session-revocation check.
--
-- JwtAuthFilter now asks, on every authenticated request, whether the session named by the access
-- token's sid claim still has a live refresh token (SessionValidator). Without that question being
-- asked, a revocation — logout, "sign out of that device", the idle timeout, the absolute cap, a
-- password change, or the account-wide revocation reuse detection performs when it concludes a
-- token was stolen — could not reach an access token already in circulation, and the token kept
-- working for the remainder of its fifteen minutes.
--
-- V57 already indexes session_id, and that index is what makes the question answerable at all. It
-- is not what makes it cheap. Rotation writes a NEW row carrying the same session_id roughly every
-- fifteen minutes and revokes the old one, and nothing purges the revoked rows (they are the audit
-- trail of a session's rotations), so a session running to its seven-day absolute cap accumulates
-- hundreds of rows under one session_id. The full index would return all of them on every request
-- and filter revoked_at afterwards.
--
-- A partial index over the unrevoked rows holds exactly one entry per LIVE session, no matter how
-- long that session has been rotating. The predicate below is a strict subset of the one the query
-- uses (`session_id = ? AND revoked_at IS NULL AND expires_at > now()`), which is what lets the
-- planner use it: expires_at is left to be checked on the heap tuple because now() is not
-- immutable and cannot appear in an index predicate.
--
-- V57's index is deliberately kept. It covers session_id lookups that are not restricted to live
-- rows, which is what any future "show me this session's rotation history" question needs, and
-- dropping an index to save writes on a table this small is an optimisation nobody has measured.
CREATE INDEX idx_refresh_tokens_live_session
    ON refresh_tokens (session_id)
    WHERE revoked_at IS NULL;

COMMENT ON INDEX idx_refresh_tokens_live_session IS
    'Serves SessionValidator''s per-request "is this session still live" check. One entry per live '
    'session rather than one per rotation.';
