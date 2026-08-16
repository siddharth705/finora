-- Gmail OAuth connection state -- Phase B of the Gmail Transaction Sync design
-- (docs/proposals/gmail-transaction-sync-proposal.md). Connection lifecycle ONLY: no sync worker,
-- no parsers, no ingestion. Those come later and add no columns here.
--
-- TWO TABLES, AND WHY THE SECOND ONE IS NOT OPTIONAL
-- --------------------------------------------------
-- The obvious shape is one table. It is not sufficient, because of how OAuth actually returns.
--
-- Google redirects the user's BROWSER back to the callback URL. That request carries no
-- Authorization header and no session -- this API is stateless (SecurityConfig sets
-- SessionCreationPolicy.STATELESS), so there is nothing on the callback request that says which
-- Finora user it belongs to. The only channel that survives the round trip is the `state`
-- parameter, which Google echoes back verbatim.
--
-- That makes `state` load-bearing for identity, not just CSRF. It therefore has to be unguessable,
-- bound to the user who started the flow, short-lived, and SINGLE USE. The last of those is what
-- forces a table: a signed/stateless token can carry a user id and an expiry, but nothing
-- stateless can enforce "this may be redeemed exactly once". Without that, a replayed callback URL
-- -- out of browser history, a referrer header, a shared screenshot -- re-runs the link.
--
-- So: gmail_oauth_states holds the pending flows, and rows are consumed on first use.

CREATE TABLE gmail_connections (
    id UUID PRIMARY KEY,

    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- Google's stable subject identifier ("sub"), NOT the email address. An email can change --
    -- Google account renames, Workspace domain migrations -- while `sub` is documented as stable
    -- for the lifetime of the account. Keying identity off email would silently create a second
    -- connection for the same mailbox the day someone renames theirs.
    google_user_id VARCHAR(255) NOT NULL,

    -- Display/reference only, so the user can see WHICH mailbox is connected. Never used to
    -- resolve identity -- see above.
    google_email VARCHAR(320) NOT NULL,

    -- AES-256-GCM ciphertext from EncryptionService (ADR-007), base64 of [IV || ciphertext+tag].
    -- Nullable because a DISCONNECTED/REVOKED row keeps its audit trail (who connected what, when)
    -- while deliberately no longer holding a credential -- disconnect clears this rather than
    -- deleting the row.
    encrypted_refresh_token TEXT,

    -- Which key encrypted the value above. Without this, a key rotation cannot tell which rows are
    -- already migrated, and re-encryption becomes a flag-day migration performed under whatever
    -- incident prompted the rotation. See ADR-007.
    encryption_key_id VARCHAR(64),

    -- What Google actually granted, recorded as returned rather than as requested. These can
    -- differ: a user may decline individual scopes on the consent screen, and a token that is
    -- missing gmail.readonly must be detectable here rather than discovered on the first sync.
    granted_scopes TEXT NOT NULL,

    -- CONNECTED        -- usable; refresh token present
    -- REAUTH_REQUIRED  -- token rejected by Google (password change, user revoked access from
    --                     their Google account, suspicious-activity lock). Needs the user, not a
    --                     retry: see the proposal's error-handling table.
    -- DISCONNECTED     -- user disconnected from within Finora
    -- REVOKED          -- revoked at Google's end and detected by us
    status VARCHAR(32) NOT NULL,

    connected_at TIMESTAMPTZ,
    last_synced_at TIMESTAMPTZ,          -- stays null through Phase B; nothing syncs yet
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ONE LIVE CONNECTION PER USER (v1 scope: a single Gmail account each).
-- Partial, so a user who disconnects and reconnects is not blocked by their own historical rows --
-- only one CONNECTED/REAUTH_REQUIRED row may exist at a time.
CREATE UNIQUE INDEX uq_gmail_connections_active_user
    ON gmail_connections (user_id)
    WHERE status IN ('CONNECTED', 'REAUTH_REQUIRED');

-- ONE FINORA ACCOUNT PER GMAIL MAILBOX, for the same live statuses.
-- Without this, two Finora accounts could both connect the same mailbox and both ingest the same
-- receipts -- each unaware of the other, and each attributing the spending to a different person.
-- Keyed on google_user_id rather than google_email, for the stability reason above.
CREATE UNIQUE INDEX uq_gmail_connections_active_google_account
    ON gmail_connections (google_user_id)
    WHERE status IN ('CONNECTED', 'REAUTH_REQUIRED');

CREATE INDEX idx_gmail_connections_user ON gmail_connections (user_id, created_at DESC);


-- Pending OAuth flows. One row per "user pressed Connect", consumed when Google redirects back.
CREATE TABLE gmail_oauth_states (
    id UUID PRIMARY KEY,

    -- SHA-256 of the state value, never the value itself. The raw state travels through the user's
    -- browser and Google's servers and lands in referrer headers and browser history; anyone
    -- holding it can complete a link for the user it is bound to. Same reasoning TokenHasher
    -- already applies to refresh tokens -- this is a bearer value we only ever need to COMPARE, so
    -- there is no reason to be able to read it back. A leaked database therefore yields no usable
    -- states.
    state_hash VARCHAR(64) NOT NULL UNIQUE,

    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- Where to send the browser after the callback finishes. Captured at /connect time and
    -- validated against an allowlist THERE, so the callback cannot be turned into an open redirect
    -- by an attacker crafting a state.
    return_path VARCHAR(512),

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Short. A consent screen is completed in a minute or abandoned; a state valid for hours is
    -- just a longer window for a leaked URL to be replayed.
    expires_at TIMESTAMPTZ NOT NULL,

    -- Set on redemption. Enforces single use: a second callback carrying the same state finds this
    -- populated and is rejected. Kept (rather than deleting the row) so a replay is distinguishable
    -- from an unknown state in the audit trail.
    consumed_at TIMESTAMPTZ
);

-- Supports the expiry sweep, which walks oldest-first.
CREATE INDEX idx_gmail_oauth_states_expires ON gmail_oauth_states (expires_at);
