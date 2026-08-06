-- Deliverable 0 of the Import Reliability Milestone
-- (docs/engineering/import-reliability-milestone-design.md).
--
-- Merchant learning stops sharing a transaction with the import that triggers it. This table is
-- the durable handoff between the two: the import writes a row here inside its OWN transaction,
-- and a worker processes it after that transaction has committed.
--
-- Why a table and not an in-memory queue. Two reasons, both operational rather than theoretical:
-- a deploy is a restart, and an in-memory queue loses everything not yet processed; and Railway
-- can run more than one instance, so any design where a worker assumes it is alone will
-- double-process. Double-processing is not merely wasted work here -- applying one learning event
-- twice increments a merchant's confirmation_count twice, and confirmation counts are what
-- ConfidenceEngine.topCategory uses to decide which category is auto-applied. The corruption is
-- silent and shows up later as the wrong category.
--
-- The claim query (MerchantLearningEventRepository) uses FOR UPDATE SKIP LOCKED so a row already
-- claimed by one worker is invisible to the others rather than contended. Same discipline as
-- ImportSessionRepository.claimForConfirmation, which uses an atomic conditional UPDATE to stop
-- two concurrent confirms importing one statement twice.
CREATE TABLE merchant_learning_events (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- CASCADE on merchant/category: an event that refers to a merchant or category which no
    -- longer exists is not retryable, it is meaningless. Deleting the parent should take it with
    -- them rather than leaving a row that can only ever fail.
    user_id                     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    merchant_id                 UUID NOT NULL REFERENCES merchants(id) ON DELETE CASCADE,
    category_id                 UUID NOT NULL REFERENCES categories(id) ON DELETE CASCADE,

    -- SET NULL, not CASCADE: the event is still perfectly processable after the statement it came
    -- from is deleted. This column exists so an admin looking at a failed event can see which
    -- import produced it, not because the event depends on it.
    source_statement_import_id  UUID REFERENCES statement_imports(id) ON DELETE SET NULL,

    -- PENDING -> PROCESSING -> COMPLETED, or PENDING -> PROCESSING -> PENDING (retry) -> ... ->
    -- FAILED after the fifth attempt. Kept as a string rather than a Postgres enum, matching every
    -- other status column in this schema (import_sessions.status, users.status) -- adding a value
    -- to a native enum needs a migration, and these lists are expected to grow.
    status                      VARCHAR(16)  NOT NULL DEFAULT 'PENDING',

    -- 0 until the first failure. Bounded at 5 by the worker, not by a constraint: the cap is a
    -- policy that may be tuned, and a CHECK here would turn tuning it into a migration.
    attempt_count               INT          NOT NULL DEFAULT 0,

    -- Exponential backoff: now() + 2^attempt_count minutes (1, 2, 4, 8, 16). Defaulting to now()
    -- means a freshly enqueued event is immediately due, which is the normal case -- the nudge
    -- usually beats the poller to it.
    next_attempt_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- The last failure's message, for the admin queue page (WI2). Truncated by the worker before
    -- it is written; TEXT rather than VARCHAR so a truncation bound can be changed in code.
    last_error                  TEXT,

    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    first_failed_at             TIMESTAMPTZ,
    last_retry_at               TIMESTAMPTZ
);

-- The claim query's exact predicate: WHERE status = ? AND next_attempt_at <= now() ORDER BY
-- next_attempt_at. One index serves the filter and the ordering together, so a backlog does not
-- turn every poll into a scan.
CREATE INDEX idx_merchant_learning_events_due
    ON merchant_learning_events (status, next_attempt_at);

-- Backs the admin queue page's per-merchant view and, more importantly, makes "has this merchant
-- got anything outstanding" cheap for the Merchant Review Center (WI4).
CREATE INDEX idx_merchant_learning_events_merchant
    ON merchant_learning_events (merchant_id);

COMMENT ON TABLE merchant_learning_events IS
    'Durable queue decoupling merchant learning from the import transaction that triggers it. '
    'Rows are inserted inside the import transaction and processed after it commits, so a learning '
    'failure can never roll back an import. See docs/engineering/import-reliability-milestone-design.md.';

COMMENT ON COLUMN merchant_learning_events.next_attempt_at IS
    'When this event next becomes eligible for a worker to claim. Exponential backoff on failure: '
    'now() + 2^attempt_count minutes.';
