-- Server-side state machine for the OTP-gated Change Phone Number flow, reached from
-- VerifyPhone.tsx's OTP-failure screen: enter new number -> OTP sent to and verified against the
-- NEW number -> commit. Mirrors password_change_sessions (see that table's own migration,
-- V41__password_change_sessions.sql) with one structural difference this flow actually needs: the
-- OTP here proves control of the number the user is MOVING TO, not the one already on the
-- account, so this table tracks both numbers explicitly rather than reading "current" off the
-- users row at read time, which would drift if start() and complete() ever raced with some other
-- update to it.
--
-- version starts here rather than arriving in a follow-up migration -- password_change_sessions
-- needed one (V48) because the double-submit race it fixes (two concurrent completions both
-- passing the status check before either commits) was only discovered after that table shipped.
-- The same race applies here from day one, so there is no reason to ship without it and add it
-- back later.
CREATE TABLE phone_change_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    status VARCHAR(20) NOT NULL,
    current_phone_number VARCHAR(20) NOT NULL,
    requested_phone_number VARCHAR(20) NOT NULL,
    otp_verified_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    verification_provider VARCHAR(32),
    verified_phone_number VARCHAR(20),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_phone_change_sessions_user_id ON phone_change_sessions(user_id);
