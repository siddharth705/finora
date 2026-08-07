-- Phase 1 of docs/engineering/enterprise-scale-milestone-design.md: the durable import job queue.
--
-- Import currently runs inline on the request thread. That bounds throughput to the web tier, holds
-- a connection from a pool capped at 10 for the whole parse, and gives a user uploading a large
-- statement a request that either takes minutes or times out with the work half done and no record
-- of it. This table is what lets the upload return immediately and the work happen elsewhere.
--
-- SHAPED AFTER merchant_learning_events, DELIBERATELY
-- ---------------------------------------------------
-- That queue already solved claiming (SKIP LOCKED), retry with backoff, dead-lettering, and
-- recovery of rows abandoned by a worker that died mid-apply. Inventing a second vocabulary for the
-- same problems would mean two sets of operational habits, two runbooks and two ways to be wrong.
-- The status/attempt_count/next_attempt_at/last_error columns are the same idea with the same names
-- so WorkerObservability's lifecycle -- claimed, started, completed, retry scheduled, dead letter,
-- recovered -- describes both without translation.

CREATE TABLE import_jobs (
    id                UUID PRIMARY KEY,
    user_id           UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- Where the uploaded bytes live. Content-addressed, so a retry re-reads exactly the document
    -- the user uploaded rather than trusting anything reconstructed. Nullable only because storage
    -- is still optional (app.statement-storage.provider unset keeps bytes in the database);
    -- becomes mandatory when Phase 4 of the storage migration lands.
    content_hash      VARCHAR(64),
    object_key        TEXT,
    file_name         TEXT         NOT NULL,

    -- QUEUED -> PARSING -> ANALYZING -> DEDUPING -> IMPORTING -> LEARNING -> COMPLETED
    -- with FAILED terminal and CANCELLED reachable only before IMPORTING.
    --
    -- Stage and status are ONE column, not two. A separate "status" and "stage" can disagree --
    -- FAILED at stage IMPORTING says nothing about whether the import happened -- and every such
    -- pair eventually does. The lifecycle is a single state machine; the table says so.
    status            VARCHAR(32)  NOT NULL DEFAULT 'QUEUED',

    -- Progress, for the polling endpoint. rows_total is null until PARSING has counted them, which
    -- is itself information: the UI can say "reading your statement" rather than "0 of 0".
    rows_total        INT,
    rows_processed    INT          NOT NULL DEFAULT 0,

    -- Retry bookkeeping, same semantics as the learning queue: next_attempt_at gates claiming, so a
    -- row backing off is not claimable and not counted as waiting.
    attempt_count     INT          NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_error        TEXT,

    -- Ties this job to the request that created it and to every log line, audit row and Sentry
    -- event the worker later produces. See WorkerObservability's correlation convention.
    correlation_id    VARCHAR(64),

    -- What the job produced, once it has produced it. Set at COMPLETED so the progress endpoint can
    -- hand the caller somewhere to go without a second lookup.
    import_session_id UUID,

    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at        TIMESTAMPTZ,
    finished_at       TIMESTAMPTZ,

    CONSTRAINT import_jobs_status_valid CHECK (status IN (
        'QUEUED','PARSING','ANALYZING','DEDUPING','IMPORTING','LEARNING',
        'COMPLETED','FAILED','CANCELLED'))
);

-- The claim query's exact predicate: claimable rows ordered by when they became due. Partial so the
-- index stays proportional to the backlog rather than to every job ever run -- on this table the
-- overwhelming majority of rows are COMPLETED and will never be claimed again.
CREATE INDEX idx_import_jobs_claimable
    ON import_jobs (next_attempt_at)
    WHERE status = 'QUEUED';

-- Recovery: rows a worker claimed and abandoned. Also partial, and deliberately covering every
-- in-flight status rather than one, because a worker can die at any stage.
CREATE INDEX idx_import_jobs_in_flight
    ON import_jobs (started_at)
    WHERE status IN ('PARSING','ANALYZING','DEDUPING','IMPORTING','LEARNING');

-- Backs the user's own "my imports" view and the progress endpoint's ownership check.
CREATE INDEX idx_import_jobs_user ON import_jobs (user_id, created_at DESC);

COMMENT ON TABLE import_jobs IS
    'Durable queue for statement imports. Postgres is the system of record (ADR-003); a broker, if '
    'ever added, distributes notifications and never owns this state.';

COMMENT ON COLUMN import_jobs.status IS
    'Combined status and stage -- one state machine, so the two can never disagree.';

COMMENT ON COLUMN import_jobs.next_attempt_at IS
    'Gates claiming. A row backing off between retries is not claimable and is not "waiting".';
