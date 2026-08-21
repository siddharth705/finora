-- SEC-03 (docs/quality/bug-reports/2026-08-19-security-review-findings.md). Opt-in TOTP MFA for
-- SCOPE_ADMIN accounts. Deliberately self-service enrollment, not force-enabled for any existing
-- admin session: forcing it on would risk locking the only admin an installation has out of the
-- admin portal the moment this deploys, which is a materially worse outcome than the vulnerability
-- it closes. See AdminMfaService's own class doc for the full design.
--
-- TWO TABLES, NOT ONE, AND WHY
-- ----------------------------
-- admin_totp_credentials is 0-or-1 row per user (a real UNIQUE constraint, not just convention) --
-- an account has one authenticator enrollment or none. admin_mfa_recovery_codes is 0-to-many: a
-- fixed batch minted once at enrollment confirmation, each independently one-time-use. Folding
-- both into one row (recovery codes as an array column) would make "mark this one code used"
-- either a whole-row rewrite or a piece of application logic reaching into an array by index --
-- a separate table with its own primary key is what makes "this code, once" a real row identity
-- instead of a convention two different call sites have to agree on.
CREATE TABLE admin_totp_credentials (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    -- EncryptedValue's two halves (ADR-007, same convention as GmailConnection's
    -- encrypted_refresh_token/encryption_key_id) -- never store the raw Base32 secret.
    encrypted_secret    TEXT NOT NULL,
    encryption_key_id   VARCHAR(64) NOT NULL,
    -- False from enrollment start until the user proves they can actually generate a valid code
    -- with it (AdminMfaService.confirm) -- a secret alone proves nothing; a scanned QR code could
    -- have been mis-scanned, or the enrollment abandoned before the app was ever set up.
    -- login() only ever checks this column, never row-existence alone.
    enabled             BOOLEAN NOT NULL DEFAULT false,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    enabled_at          TIMESTAMPTZ,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Hashed, one-way (TokenHasher.sha256, same as password-reset/reactivation tokens) -- a recovery
-- code only ever needs comparing, never reproducing, so hashing it is strictly safer than the
-- reversible encryption the TOTP secret above needs (see EncryptionService's own class doc on
-- choosing between the two).
CREATE TABLE admin_mfa_recovery_codes (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code_hash   VARCHAR(64) NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_admin_mfa_recovery_codes_user ON admin_mfa_recovery_codes(user_id);

-- SEC-07's own migration (V97, a sibling PR not yet on this branch) already established the
-- pattern this project uses for a short-lived, single-use, hashed server-side challenge: this
-- reuses that same shape rather than inventing a third. A challenge is minted the moment login()
-- confirms the password was correct and the account has MFA enabled, and is consumed (or expires)
-- within minutes -- there is no reason for it to outlive the login attempt it belongs to.
CREATE TABLE admin_mfa_challenges (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash   VARCHAR(64) NOT NULL UNIQUE,
    expires_at   TIMESTAMPTZ NOT NULL,
    used_at      TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_admin_mfa_challenges_token_hash ON admin_mfa_challenges(token_hash);
