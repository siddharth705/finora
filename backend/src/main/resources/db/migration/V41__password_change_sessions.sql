-- OTP purpose: a code issued for phone verification (REGISTER_PHONE) must not be usable to
-- satisfy a password-reset or password-change OTP prompt, and vice versa. Existing rows predate
-- this concept and were all issued for phone verification, so they default to REGISTER_PHONE
-- rather than being left ambiguous.
ALTER TABLE phone_otps ADD COLUMN purpose VARCHAR(32) NOT NULL DEFAULT 'REGISTER_PHONE';

-- Server-side state machine for the OTP-gated Change Password flow: current-password check ->
-- OTP verification -> completion, each step validated against this row rather than trusted from
-- client-asserted "I already did step N" claims. expires_at bounds the whole session (not just
-- the OTP's own shorter TTL) so an abandoned flow can't be resumed indefinitely.
CREATE TABLE password_change_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    status VARCHAR(20) NOT NULL,
    current_password_verified_at TIMESTAMPTZ NOT NULL,
    otp_verified_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_password_change_sessions_user_id ON password_change_sessions(user_id);
