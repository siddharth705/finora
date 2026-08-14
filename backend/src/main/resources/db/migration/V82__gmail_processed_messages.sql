-- One row per Gmail message Finora has ever looked at -- Phase C4, design proposal §7.
--
-- TWO JOBS: IDEMPOTENCY AND PROVENANCE
-- ------------------------------------
-- Idempotency, because a sync is not a transaction. A run that dies halfway -- a killed process, a
-- rate limit, an expired token -- must be safely resumable, and the only way to resume without
-- re-processing is to know what has already been seen. The unique constraint below is what makes a
-- retry cheap instead of duplicative.
--
-- Provenance, because "why did this transaction appear?" and "why did this receipt NOT appear?" are
-- both questions users and support will ask, and neither is answerable from the ledger alone. A
-- message that was skipped leaves no transaction to inspect; this table is the only place its fate
-- is written down.
--
-- WHAT IS DELIBERATELY NOT HERE
-- -----------------------------
-- No subject, no sender address, no body, no snippet. Per the design proposal's §12.4 reasoning:
-- unlike a bank statement -- where Finora's copy is the only durable record -- the source email
-- already lives in the user's own mailbox, and a receipt carries markedly more incidental personal
-- data than a statement does (delivery addresses, phone numbers, other people's names). The Gmail
-- message id is enough to re-fetch for debugging, and holding anything more would duplicate
-- sensitive content at rest for no benefit Finora could not get on demand.
--
-- The one exception is the authenticated sender DOMAIN, which is not personal data, and which is
-- the whole "which parser should we write next" signal (§16.1).

CREATE TABLE gmail_processed_messages (
    id UUID PRIMARY KEY,

    connection_id UUID NOT NULL REFERENCES gmail_connections(id) ON DELETE CASCADE,

    -- Gmail's own immutable id for the message within this mailbox.
    gmail_message_id VARCHAR(128) NOT NULL,

    -- SKIPPED_UNTRUSTED_SENDER -- failed DKIM/SPF/DMARC, or the authenticated domain is not on the
    --                             trusted registry. The body was never fetched.
    -- SKIPPED_NOT_RECEIPT      -- from a trusted sender, but nothing about it looks transactional.
    -- DETECTED_NOT_STAGED      -- trusted and receipt-shaped, but no merchant-specific parser
    --                             handles it. Recorded, never staged (design proposal §10.3).
    -- PARSED / PARSE_FAILED    -- reserved for C5. No row carries either yet; nothing parses.
    outcome VARCHAR(40) NOT NULL,

    -- The domain that actually passed authentication -- never the From header as written. Null when
    -- nothing passed. Not personal data, and the signal behind "N users receive receipts from a
    -- merchant we cannot read yet".
    authenticated_domain VARCHAR(253),

    -- Why the gate refused, when it did. Distinguishing "not authenticated" from "authenticated but
    -- not on the registry" matters: the first is a spoof or a delivery problem, the second is a
    -- merchant we simply have not added.
    skip_reason VARCHAR(40),

    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Constrained in the database, following import_jobs_status_valid. This table is what support
    -- reads to answer "why did nothing appear for this receipt?", so a typo'd outcome would not
    -- break anything loudly -- it would quietly produce an unanswerable row.
    CONSTRAINT gmail_processed_outcome_valid CHECK (outcome IN (
        'SKIPPED_UNTRUSTED_SENDER','SKIPPED_NOT_RECEIPT','DETECTED_NOT_STAGED',
        'PARSED','PARSE_FAILED')),

    -- Mirrors SenderAuthenticationService.Verdict, minus TRUSTED -- a trusted message is not a skip,
    -- and allowing it here would let a row claim it was both.
    CONSTRAINT gmail_processed_skip_reason_valid CHECK (skip_reason IS NULL OR skip_reason IN (
        'NOT_AUTHENTICATED','DOMAIN_NOT_TRUSTED','NO_AUTHENTICATION_HEADER'))
);

-- THE IDEMPOTENCY GUARANTEE. A message is processed at most once per connection, and the database
-- enforces it rather than the worker remembering to check -- two overlapping runs, or a resumed run
-- that re-reads a page, both lose here instead of double-recording.
CREATE UNIQUE INDEX uq_gmail_processed_message
    ON gmail_processed_messages (connection_id, gmail_message_id);

-- The aggregate reads: what happened on this connection lately, and which unparsed domains are
-- worth writing a parser for.
CREATE INDEX idx_gmail_processed_connection ON gmail_processed_messages (connection_id, processed_at DESC);
CREATE INDEX idx_gmail_processed_outcome_domain ON gmail_processed_messages (outcome, authenticated_domain);


-- Where incremental sync resumes from. Gmail's historyId is a per-mailbox cursor: given one, the
-- API returns only what changed since. Stored on the connection rather than in a separate table
-- because it is exactly one value per connection and shares its lifecycle.
--
-- Null means "never synced" -- the first run establishes a starting point rather than walking the
-- entire mailbox. See the bounded initial window in the discovery service.
ALTER TABLE gmail_connections ADD COLUMN history_cursor VARCHAR(64);

-- When the last completed discovery run finished, so a user-facing panel can say "last checked 10
-- minutes ago" and support can tell a stalled connection from an idle one.
--
-- Deliberately distinct from last_synced_at, which V80 reserved for actual transaction sync (C5)
-- and which stays null throughout C4: conflating "we looked" with "we imported something" would
-- make the panel lie in exactly the period where nothing is imported yet.
ALTER TABLE gmail_connections ADD COLUMN last_discovery_at TIMESTAMPTZ;
