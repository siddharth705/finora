-- Absolute session lifetime needs to survive rotation, and created_at cannot carry it.
--
-- Every refresh rotates: the old row is revoked and a NEW row is inserted, so its created_at is
-- the time of the last refresh, not the time the user signed in. Measuring an absolute cap from
-- created_at would therefore measure nothing at all -- it resets every fifteen minutes, which is
-- exactly the perpetual sliding session the cap exists to end.
--
-- session_started_at is set once at sign-in and copied forward unchanged by every rotation, so it
-- keeps meaning "when did this session begin" however many times the token has been exchanged.
ALTER TABLE refresh_tokens ADD COLUMN session_started_at TIMESTAMPTZ;

-- Existing tokens have no recorded session start. created_at is the closest honest answer: for a
-- token that has never rotated it IS the sign-in time, and for one that has it understates the
-- session's age, which fails safe -- the cap arrives sooner than the true elapsed time, never
-- later. The alternative, leaving them NULL and exempting them, would let every session alive at
-- deploy time keep the old unlimited behaviour indefinitely.
UPDATE refresh_tokens SET session_started_at = created_at WHERE session_started_at IS NULL;

ALTER TABLE refresh_tokens ALTER COLUMN session_started_at SET NOT NULL;

COMMENT ON COLUMN refresh_tokens.session_started_at IS
    'When the user signed in. Copied forward across rotations, unlike created_at, so it bounds the '
    'total session lifetime rather than the age of the current token.';
