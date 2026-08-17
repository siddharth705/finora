-- D-23 self-review finding: Google sign-in's auto-link (loginWithGoogle) trusted a match on
-- `email` alone to sign a caller into an existing account -- but self-service password
-- registration (createUserRecord) never verified that whoever registered an email actually owns
-- it. An attacker could pre-register a victim's email with a password of their own choosing, and
-- when the real victim later signed in with Google, auto-link would sign them into the attacker's
-- account, which the attacker still holds the password to -- the classic OAuth "pre-hijacking"
-- attack. This column, and the verification flow it backs (AuthService.mintEmailVerificationToken
-- / verifyEmail), close that: loginWithGoogle only auto-links into an account whose email is
-- already verified.
--
-- Existing rows are backfilled to true (DEFAULT true at add-time, applied to every row that
-- exists right now), not false -- this is a prospective fix for accounts created going forward,
-- not a retroactive re-verification requirement for users who were already using the product
-- before this existed. The DEFAULT is then narrowed to false so every NEW row (going through the
-- ORM, which always writes an explicit value, or any future raw INSERT that doesn't) starts
-- unverified until AuthService.verifyEmail actually confirms it -- mirroring phone_verified
-- (V8__phone_otp_verification.sql)'s own column shape exactly.
ALTER TABLE users ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE users ALTER COLUMN email_verified SET DEFAULT false;

-- Same shape as password_reset_tokens (V2) and account_reactivation_tokens (V87), for the
-- identical reason: a raw, unguessable, single-use, short-TTL token stored hashed so a DB leak
-- alone doesn't hand out a working verification link. Minted by
-- AuthService.mintEmailVerificationToken (called from register(), and from loginWithGoogle() when
-- it finds a matching but not-yet-verified existing account), consumed by
-- AuthService.verifyEmail().
--
-- No separate index on token_hash: the UNIQUE constraint below already creates one -- see
-- account_reactivation_tokens' own migration comment on why this isn't repeated as a second,
-- functionally-overlapping index.
CREATE TABLE email_verification_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  VARCHAR(64) NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
