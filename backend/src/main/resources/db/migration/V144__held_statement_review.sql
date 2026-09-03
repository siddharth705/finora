-- Held Statement Review, Plan 1: quarantine an import whose extraction evidence says it may be
-- wrong, instead of letting it reach a user's ledger unreviewed.
--
-- Storage is untouched. held_statements.statement_object_key names the SAME R2 object
-- import_jobs.object_key already names -- nothing is copied, nothing is moved, and the
-- content-addressed sharing StatementStorageSweepService depends on is preserved exactly. A hold
-- is a workflow state, not a storage state.

-- A new terminal status, distinct from HELD_FOR_REVIEW (V134), which that status's own doc scopes
-- to "a human fixing a parser" -- an operator-remediable pipeline failure. This is the other kind
-- of hold: a judgment call about a document that parsed fine but whose own evidence contradicts
-- the extraction.
ALTER TABLE import_jobs DROP CONSTRAINT import_jobs_status_valid;
ALTER TABLE import_jobs ADD CONSTRAINT import_jobs_status_valid CHECK (status IN (
    'QUEUED', 'PARSING', 'ANALYZING', 'DEDUPING', 'IMPORTING', 'LEARNING',
    'COMPLETED', 'FAILED', 'HELD_FOR_REVIEW', 'HELD_FOR_TRUST_REVIEW', 'CANCELLED'));

-- Mirrors V134's own precedent for HELD_FOR_REVIEW: a job waiting on a human is not "in flight"
-- for the duplicate-upload guard, so re-uploading the same statement stays possible rather than
-- being blocked indefinitely behind a pending review.
DROP INDEX idx_import_jobs_live_content;
CREATE UNIQUE INDEX idx_import_jobs_live_content
    ON import_jobs (user_id, content_hash)
    WHERE content_hash IS NOT NULL
      AND status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'HELD_FOR_REVIEW',
                          'HELD_FOR_TRUST_REVIEW');

-- Restated because DROP INDEX takes the comment with it, and V134's warning is the reason this
-- migration had to touch the index at all.
COMMENT ON INDEX idx_import_jobs_live_content IS
    'One live job per user per document. Terminal rows are excluded so re-uploading a statement '
    'whose earlier import finished, failed, was cancelled, or is held for review stays possible. '
    'This predicate must track ImportJob.Status.TERMINAL exactly -- see V134 for what breaks when '
    'they drift.';

-- Back-reference, so "is this import held, and which review is it" is answerable from the job row
-- without a join. Nullable: NULL means this import was never held.
ALTER TABLE import_jobs ADD COLUMN held_statement_id UUID;

-- HELD_FOR_TRUST_REVIEW is deliberately NOT added to
-- StatementStorageSweepService.IMPORT_JOB_EXCLUDED_STATUSES, which stays {COMPLETED, CANCELLED}.
-- That set decides whether an import_jobs row counts as a LIVE reference to its object. A held
-- job must keep counting as live, or the sweep could reclaim the PDF a reviewer still needs to
-- open. This is the load-bearing reason the status exists at all rather than reusing COMPLETED
-- with a side table: completing a held job would make its object reclaimable immediately.

CREATE SEQUENCE held_statement_reference_seq START 1;

CREATE TABLE held_statements (
    id                                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    held_id                           VARCHAR(32) NOT NULL UNIQUE,
    -- Both cascade, matching import_jobs.user_id's own ON DELETE CASCADE (V66). Without
    -- this, deleting an account whose import was ever held fails on a foreign key and takes
    -- the whole account-deletion path down with it.
    import_job_id                     UUID NOT NULL UNIQUE
                                          REFERENCES import_jobs(id) ON DELETE CASCADE,
    user_id                           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    statement_object_key              TEXT NOT NULL,

    -- Snapshot at hold time, not a live read. A statement held under one parser build must never
    -- be silently released under that same build, and a later re-run has to be comparable against
    -- what the original build actually saw.
    parser_version                    VARCHAR(64),
    reliability_status                VARCHAR(24),
    text_source                       VARCHAR(24),
    header_reconstruction_uncertain   BOOLEAN,

    status                            VARCHAR(32) NOT NULL CHECK (status IN (
                                          'HELD', 'ASSIGNED', 'INVESTIGATING',
                                          'READY_FOR_IMPORT', 'IMPORTED', 'REJECTED')),

    assigned_engineer_id              UUID REFERENCES users(id) ON DELETE SET NULL,
    trigger_summary                   TEXT,
    engineer_notes                    TEXT,

    created_at                        TIMESTAMPTZ NOT NULL DEFAULT now(),
    assigned_at                       TIMESTAMPTZ,
    ready_at                          TIMESTAMPTZ,
    resolved_at                       TIMESTAMPTZ,

    -- SET NULL, never CASCADE: deleting an admin account must not erase the record
    -- of what they approved or rejected. The history outlives the actor.
    created_by                        UUID REFERENCES users(id) ON DELETE SET NULL,
    resolved_by                       UUID REFERENCES users(id) ON DELETE SET NULL
);

-- The queue's ordering: oldest first, matching every other operator queue in this codebase
-- (AdminHeldImportService.list, AdminLearningQueueService) -- the longest-waiting user is the one
-- to look at.
CREATE INDEX idx_held_statements_open ON held_statements (created_at)
    WHERE status IN ('HELD', 'ASSIGNED', 'INVESTIGATING', 'READY_FOR_IMPORT');

COMMENT ON COLUMN held_statements.statement_object_key IS
    'The same R2 key import_jobs.object_key already names. Never a copy, never moved -- this row '
    'tracks a workflow state over one shared object.';

COMMENT ON COLUMN held_statements.parser_version IS
    'The deploy that parsed this statement, snapshotted at hold time. A hold must never be '
    'auto-released under the same build that produced it.';

COMMENT ON COLUMN held_statements.trigger_summary IS
    'Human-readable summary of every trust condition that fired, from TrustPredicate. Not a new '
    'signal -- a rendering of evidence the pipeline already computed.';

CREATE TABLE held_statement_events (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    held_statement_id   UUID NOT NULL REFERENCES held_statements(id) ON DELETE CASCADE,
    actor_id            UUID REFERENCES users(id) ON DELETE SET NULL,
    event_type          VARCHAR(64) NOT NULL,
    from_status         VARCHAR(32),
    to_status           VARCHAR(32),
    notes               TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_held_statement_events_by_statement
    ON held_statement_events (held_statement_id, created_at);

COMMENT ON TABLE held_statement_events IS
    'Every state transition and operator action, in order. Financial workflows eventually have to '
    'answer why a statement was held, who reviewed it and who released it -- captured as it '
    'happens rather than reconstructed from logs later. actor_id NULL means the system acted.';

-- Its own permission, not a reuse -- the same reasoning V135 applied to IMPORT_TRIAGE_MANAGE:
-- reviewing a hold means reading a real customer's statement content, which is a different kind
-- of access from PLATFORM_DIAGNOSTICS_VIEW's read-only pipeline visibility (V34).
INSERT INTO permissions (name, description) VALUES
    ('TRUST_REVIEW_MANAGE',
     'View and resolve statements held for trust review. Grants access to real user statement '
     'content; every resolution is audited.');

-- ADMIN and SUPER_ADMIN, matching every permission added since V24. SUPER_ADMIN needs its own
-- explicit grant -- its V16 "every permission" catch-all was a one-time snapshot, not a standing
-- rule. Both inserts are mandatory: a permission with no role_permissions row grants nothing to
-- anyone, and the queue would 403 for every admin.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name IN ('ADMIN', 'SUPER_ADMIN') AND p.name = 'TRUST_REVIEW_MANAGE';
