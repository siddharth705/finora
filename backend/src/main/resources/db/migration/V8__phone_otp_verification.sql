-- Mobile number capture at registration + OTP-based phone verification.
ALTER TABLE users ADD COLUMN phone_number VARCHAR(20);
ALTER TABLE users ADD COLUMN phone_verified BOOLEAN NOT NULL DEFAULT false;

-- OTPs are stored hashed (same reasoning as password_reset_tokens and refresh_tokens — a
-- database leak alone shouldn't hand out a usable code). attempts caps brute-force guessing:
-- a 6-digit code only has 1,000,000 possibilities, so unlike a 32-byte random token, this
-- MUST be attempt-limited, not just expiry-limited.
CREATE TABLE phone_otps (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    phone_number    VARCHAR(20) NOT NULL,
    otp_hash        VARCHAR(64) NOT NULL,
    attempts        INT NOT NULL DEFAULT 0,
    expires_at      TIMESTAMPTZ NOT NULL,
    verified_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_phone_otps_user ON phone_otps(user_id, created_at DESC);
