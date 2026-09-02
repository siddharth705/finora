-- Device push tokens, encrypted at rest. Storage half of mobile push (Task 9) -- the dispatcher
-- worker (already shipped) and the FCM/APNs providers (Task 11) come later and add no columns
-- here.

CREATE TABLE device_tokens (
    id                UUID PRIMARY KEY,
    user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    platform          VARCHAR(16) NOT NULL,
    encrypted_token   TEXT NOT NULL,       -- AES-256-GCM ciphertext from EncryptionService (ADR-007),
                                            -- base64 of [IV || ciphertext+tag].
    encryption_key_id VARCHAR(64) NOT NULL, -- Which key encrypted the value above. Without this, a key
                                            -- rotation cannot tell which rows are already migrated.
    token_fingerprint VARCHAR(64) NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL,
    last_seen_at      TIMESTAMPTZ NOT NULL,
    revoked_at        TIMESTAMPTZ,
    UNIQUE (user_id, token_fingerprint)
);

-- Plain multi-column UNIQUE, not a partial index: unlike the mutable-flag bug Task 7 shipped (a
-- "retired" boolean baked directly into a multi-column UNIQUE, which blocked a SECOND retirement --
-- see V125's idx_notification_templates_active comment), revoked_at is NOT one of this constraint's
-- columns at all. user_id/token_fingerprint are this row's whole identity and never change after
-- insert: token_fingerprint is a deterministic hash of the raw token's own bytes, fixed the moment
-- the row is created. revoked_at is mutated later by revoke()/touch(), entirely outside the
-- constrained tuple -- the same shape V126 already used for notification_preferences, and for the
-- same reason: there is no mutable column inside this uniqueness pair to trap a future update
-- against.
--
-- DeviceTokenService.register is find-by-(user_id, token_fingerprint)-then-touch, never a blind
-- insert, so re-registering a token that was previously revoked reactivates the SAME row (clearing
-- revoked_at) instead of inserting a second one -- exactly the "second retirement" scenario the
-- Task 7 bug broke, and this shape does not reproduce it. A partial index scoped to WHERE
-- revoked_at IS NULL was considered and rejected: it would let a second, genuinely NEW row be
-- inserted for a (user, token) pair that already has a revoked row on file (since the old row falls
-- outside the partial index's scope and no longer blocks it), silently forking one physical token's
-- history across two rows instead of surfacing the race as the 409 CONFLICT
-- GlobalExceptionHandler.handleDataIntegrityViolation already returns for exactly this class of
-- check-then-act collision. The plain constraint is the stricter, more correct guarantee here.
CREATE INDEX idx_device_tokens_active
    ON device_tokens (user_id)
    WHERE revoked_at IS NULL;

COMMENT ON TABLE device_tokens IS
    'Push tokens, encrypted at rest. Encrypted and NOT hashed on purpose: the dispatcher hands the '
    'real token to FCM/APNs on every send, so it must be recoverable -- a one-way hash would make '
    'this table useless for its own purpose.';
COMMENT ON COLUMN device_tokens.token_fingerprint IS
    'SHA-256 of the raw token, for equality lookups only. AES-GCM uses a fresh random IV per call, '
    'so the same token never encrypts to the same ciphertext and the encrypted column cannot be '
    'matched on directly.';
COMMENT ON COLUMN device_tokens.revoked_at IS
    'Soft revoke on logout or uninstall detection, never a hard delete, so the trail survives.';
