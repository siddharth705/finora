-- Widens V23's status CHECK constraint to add the self-service account lifecycle's first phase:
-- DEACTIVATED (reversible, user-initiated). Existing rows are untouched; every one is still ACTIVE
-- or SUSPENDED, so dropping and re-adding the constraint with the wider set cannot reject any of
-- them.
--
-- Deliberately does NOT yet add PENDING_DELETION/DELETED or a deletion_requested_at column --
-- those belong to the permanent-delete phase, which hasn't shipped any code that writes or reads
-- them. Shipping schema ahead of the code that uses it means a later design change to that phase
-- (retention window, column name, index shape) can only be corrected with a follow-up migration,
-- never by editing this one -- so it ships alongside that code instead, in its own migration.
ALTER TABLE users DROP CONSTRAINT users_status_check;
ALTER TABLE users ADD CONSTRAINT users_status_check
    CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DEACTIVATED'));

-- Reactivation tokens for a self-deactivated account -- same shape as password_reset_tokens
-- (V2), for the identical reason: a raw, unguessable, single-use, short-TTL token stored hashed
-- so a DB leak alone doesn't hand out a working reactivation link. Minted by AuthService.login()
-- the moment it recognizes a DEACTIVATED account, consumed by AuthService.reactivate().
--
-- No separate index on token_hash: the UNIQUE constraint below already creates one, and
-- password_reset_tokens' own idx_reset_token_hash (V2) is a pre-existing instance of adding a
-- second, functionally-overlapping index on a column that's already UNIQUE -- not a pattern worth
-- repeating here just because it was already inherited there.
CREATE TABLE account_reactivation_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  VARCHAR(64) NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
