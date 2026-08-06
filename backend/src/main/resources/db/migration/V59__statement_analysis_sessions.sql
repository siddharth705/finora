-- Every upload leaves a record, whether or not it ever becomes an import.
--
-- Until now the pipeline only remembered successes: statement_imports is written inside confirm(),
-- so a document the parser could not read left nothing at all -- no fingerprint, no failure reason,
-- no trace that it was ever tried. That discards precisely the documents worth learning from:
-- unknown layouts, unsupported products, new banks, and drift in a layout that used to work.
--
-- This is EVIDENCE, not knowledge. Rows are written automatically by the pipeline and are never
-- edited afterwards; the admin-curated layer that says "section 2 of this layout is a fixed
-- deposit, approved" is a separate table and a later change. Keeping the two apart is what makes
-- the evidence trustworthy: an observation nobody can revise, and a decision that is explicitly
-- somebody's.
--
-- Numbered V59, skipping V58: feat/phase-4-drop-file-content already holds V58 after being
-- renumbered out of a collision with V55, and reusing it here would recreate exactly that problem.
-- Flyway does not require contiguous versions.
CREATE SEQUENCE statement_analysis_reference_seq;

CREATE TABLE statement_analysis_sessions (
    id                  UUID PRIMARY KEY,

    -- Human-quotable, unique, and stable: "open analysis SA-20260806-0145" has to be something a
    -- person can read off a screen to support and support can paste back to engineering. The UUID
    -- is the key; this is the handle.
    reference           VARCHAR(24)  NOT NULL UNIQUE,

    -- Who uploaded it. Nullable because an analysis can outlive the account that produced it, and
    -- losing the whole layout observation to a user deletion would defeat the point of collecting
    -- it. There is deliberately NO foreign key for the same reason.
    user_id             UUID,

    -- CUSTOMER_IMPORT or ADMIN_ANALYSIS. The same pipeline records both, because a customer
    -- hitting an unknown layout is exactly as informative as an admin doing it deliberately --
    -- more so, since it is real usage.
    source              VARCHAR(24)  NOT NULL,

    file_name           VARCHAR(255),
    source_format       VARCHAR(8),
    byte_size           BIGINT,

    -- Null when parsing failed before a layout could be characterised -- an encrypted PDF with the
    -- wrong password never gets far enough to have a fingerprint.
    layout_fingerprint  VARCHAR(128),

    -- PARSED, FAILED. Not "SUCCESS": parsing a document is not the same as importing it, and the
    -- user may still abandon the review.
    outcome             VARCHAR(16)  NOT NULL,

    -- The ErrorCode that ended it (IMPORT_001, IMPORT_007, IMPORT_008...), so failures can be
    -- grouped by cause rather than by message text.
    failure_code        VARCHAR(32),
    failure_detail      TEXT,

    section_count       INTEGER,
    duration_ms         BIGINT,

    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- "Which layouts fail most often" is the question this table exists to answer, and it filters on
-- fingerprint and outcome together.
CREATE INDEX idx_analysis_fingerprint_outcome ON statement_analysis_sessions (layout_fingerprint, outcome);
-- "What happened today" / "what did this user hit", both ordered newest first.
CREATE INDEX idx_analysis_created_at ON statement_analysis_sessions (created_at DESC);
CREATE INDEX idx_analysis_user_id ON statement_analysis_sessions (user_id);

COMMENT ON TABLE statement_analysis_sessions IS
    'Immutable evidence: one row per upload attempt, successful or not. Written automatically, '
    'never edited. The admin-curated layout registry is a separate table.';
COMMENT ON COLUMN statement_analysis_sessions.reference IS
    'Human-quotable handle, SA-YYYYMMDD-NNNN. Support and engineering refer to the same analysis by this.';
