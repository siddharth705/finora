-- Server-side state machine for the step-up-gated Change Email flow: verify current identity
-- (password or a fresh Google/Apple credential) -> a verification link is sent to and confirmed
-- against the NEW address -> commit. Mirrors phone_change_sessions (see that table's own
-- migration, V91__phone_change_sessions.sql) with two structural differences this flow needs:
--
-- 1. verification_token_hash: phone-change proves control of the new number via a third-party
--    (Firebase) that hands back an ID token this backend verifies -- there's nothing to store.
--    Email-change mints and stores its own opaque token (same TokenHasher.sha256 pattern as
--    email_verification_tokens/password_reset_tokens), since this backend is the one issuing and
--    checking the verification link itself, not a third party.
-- 2. No verification_provider/verified_phone_number-equivalent columns: there's only ever one way
--    this flow proves control of the new address (its own minted link), unlike phone-change's
--    PhoneVerificationProvider abstraction which exists for a swappable third-party OTP source.
--
-- version included from day one (not added in a follow-up migration) -- the double-submit race it
-- guards against (two concurrent completions both passing the status check before either commits)
-- applies to every session table in this family; see phone_change_sessions' own migration comment
-- for why that lesson is applied up front here rather than rediscovered.
CREATE TABLE email_change_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    status VARCHAR(20) NOT NULL,
    current_email VARCHAR(255) NOT NULL,
    requested_email VARCHAR(255) NOT NULL,
    verification_token_hash VARCHAR(64) NOT NULL,
    email_verified_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_email_change_sessions_user_id ON email_change_sessions(user_id);
