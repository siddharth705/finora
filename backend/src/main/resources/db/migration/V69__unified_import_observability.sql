-- Item 6 of docs/engineering/milestone-2-import-at-scale.md: COMPLETING import observability.
--
-- Two thirds of the questions worth asking are already answerable. import_jobs carries status,
-- progress, attempts, last_error and correlation_id; statement_analysis_sessions carries the
-- layout fingerprint, the outcome, the failure code, the row count and the unanchored-reason
-- histogram; merchant_learning_events carries what an import taught the system. None of that is
-- rebuilt here, and nothing in this migration adds a counter, a gauge or a score.
--
-- Three things were genuinely missing, and this migration is exactly those three:
--
--   1. Per-stage timing and status. statement_analysis_sessions.duration_ms is the TOTAL, so
--      "which stage was slow" has never been answerable.
--   2. Verification outcomes. The four rules run on every staged statement, reach the staging
--      response, and are then discarded. Nobody can ask which rules ran on last week's import.
--   3. A join. The three tables above are keyed on things that do not meet, so one support
--      question is three queries plus knowing all three tables exist.
--
-- The diagnostics rule (CLAUDE.md) is what kept this small: a diagnostic earns its place by being
-- able to prove a proposed capability UNNECESSARY. Per-stage timing can show that a stage everyone
-- assumed was slow is not, and stop an optimisation being built. A SKIPPED stage row can show that
-- a stage does not run on this path at all, and stop it being optimised twice. A counter that only
-- ever goes up could do neither, which is why there is not one here.

-- ---------------------------------------------------------------------------------------------
-- 1. PER-STAGE TIMING AND STATUS
-- ---------------------------------------------------------------------------------------------
--
-- A row per stage per attempt of an import job, not a set of columns on import_jobs. Columns
-- would have to be chosen now and re-migrated whenever the lifecycle grows a stage, and they
-- cannot represent the same stage running twice -- which a retried job does by definition.
--
-- ATTEMPT IS PART OF THE KEY, DELIBERATELY. A job that fails in ANALYZING and retries runs PARSING
-- again. Without the attempt column the second run either collides with the first or silently
-- overwrites it, and "this stage took 40ms" would be the last attempt's timing wearing the whole
-- job's name. Keeping every attempt is also the only way to see that attempt 3 was slower than
-- attempt 1, which is what distinguishes a degrading dependency from a one-off.
CREATE TABLE import_job_stages (
    id          UUID PRIMARY KEY,

    job_id      UUID        NOT NULL REFERENCES import_jobs(id) ON DELETE CASCADE,

    -- One of import_jobs.status's in-flight values: PARSING, ANALYZING, DEDUPING, IMPORTING,
    -- LEARNING. Not constrained by a CHECK against that list: the lifecycle is owned by the
    -- ImportJob.Status enum, and a CHECK here would mean a migration every time a stage is added
    -- to a state machine this table only observes.
    stage       VARCHAR(32) NOT NULL,

    -- Which attempt of the job this stage belongs to, mirroring import_jobs.attempt_count at the
    -- moment the stage was entered.
    attempt     INT         NOT NULL,

    -- RUNNING while in flight; then COMPLETED, FAILED or SKIPPED.
    --
    -- SKIPPED is not padding. The Evidence Rule says every import-engine capability leaves
    -- evidence that it worked, failed, or was skipped, and a stage that never ran is the case that
    -- would otherwise be indistinguishable from a stage nobody instrumented. A job that reaches
    -- COMPLETED without entering DEDUPING has a row saying so, which is what lets someone prove
    -- that optimising DEDUPING on this path would buy nothing.
    --
    -- RUNNING is a real, readable state rather than an internal one: a row still RUNNING long
    -- after its job finished is a worker that died inside that stage, and naming which stage is
    -- the whole value of recording it on entry rather than on exit.
    outcome     VARCHAR(16) NOT NULL,

    -- All three nullable, because a SKIPPED stage genuinely has no timing. Writing 0 instead would
    -- put a zero into every average and quietly claim IMPORTING is fast when it never ran.
    started_at  TIMESTAMPTZ,
    ended_at    TIMESTAMPTZ,
    duration_ms BIGINT,

    -- Ordering that survives a SKIPPED row having no start. The stage sequence must read in order
    -- even where there is nothing to time.
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- One row per stage per attempt. This is the constraint that makes recording idempotent: a
    -- retried recorder call is rejected by the database rather than silently doubling a timing.
    CONSTRAINT import_job_stages_unique_per_attempt UNIQUE (job_id, attempt, stage),

    CONSTRAINT import_job_stages_outcome_valid
        CHECK (outcome IN ('RUNNING', 'COMPLETED', 'FAILED', 'SKIPPED'))
);

-- The only access pattern: every stage of one job, in order. Covering both columns so the trace
-- view is an index scan rather than a sort.
CREATE INDEX idx_import_job_stages_job ON import_job_stages (job_id, recorded_at);

COMMENT ON TABLE import_job_stages IS
    'Per-stage timing and status for one import job attempt. Answers "which stage was slow", which '
    'statement_analysis_sessions.duration_ms cannot -- it records only the total.';
COMMENT ON COLUMN import_job_stages.outcome IS
    'RUNNING, COMPLETED, FAILED or SKIPPED. SKIPPED records a stage that did not run, so "never '
    'ran" is distinguishable from "never instrumented".';
COMMENT ON COLUMN import_job_stages.attempt IS
    'The job attempt this stage belongs to. Part of the unique key: a retry runs earlier stages '
    'again, and merging them would report the last attempt''s timing as the job''s.';

-- ---------------------------------------------------------------------------------------------
-- 2. VERIFICATION OUTCOMES, PERSISTED
-- ---------------------------------------------------------------------------------------------
--
-- The four rules -- BALANCE_CHAIN (L3), STATEMENT_TOTALS (L4), SUMMARY_TOTALS (L5),
-- COLUMN_AMBIGUITY (L7) -- run on every staged section and their findings reach the staging
-- response. After that they are gone. "Which rules ran on this import, and what did they find" is
-- unanswerable an hour later, which also makes layout -> verification rate uncomputable
-- (import-verification-framework.md names that gap explicitly).
--
-- ONE ROW PER RULE PER SECTION, not one JSON document per import. A composite statement's sections
-- have separate balance chains and one can verify while another does not, so the section is part
-- of the identity of a finding. Rows also make "how often does SUMMARY_TOTALS report FAILED" a
-- GROUP BY instead of a scan-and-parse.
--
-- WHY TWO NULLABLE OWNERS. A verification belongs to one upload attempt, and an upload attempt is
-- identified differently on the two paths that exist: the synchronous upload records a
-- statement_analysis_sessions row, and the asynchronous worker records an import_jobs row and no
-- analysis session. Inventing a synthetic owner for whichever path lacked one would put a row in
-- an evidence table that refers to something that never happened -- the mistake V63 refused to
-- make when it declined to backfill source_import_session_id. The CHECK makes "exactly one owner"
-- a database guarantee rather than a convention.
CREATE TABLE import_verification_findings (
    id                  UUID        PRIMARY KEY,

    analysis_session_id UUID        REFERENCES statement_analysis_sessions(id) ON DELETE CASCADE,
    import_job_id       UUID        REFERENCES import_jobs(id) ON DELETE CASCADE,

    -- 0 for a single-account statement; the section's index for a composite one.
    section_index       INT         NOT NULL DEFAULT 0,

    -- The stable machine identifier the rule publishes ("BALANCE_CHAIN"), never a label.
    rule                VARCHAR(48) NOT NULL,

    -- The rule's verdict about its OWN domain: VERIFIED / WARNING / FAILED / NOT_APPLICABLE.
    -- There is deliberately no aggregate column here for the same reason VerificationReport has no
    -- overall status: combining rules needs a weighting policy, and one invented before there is
    -- anything to calibrate it against is a guess with an authoritative face.
    outcome             VARCHAR(16) NOT NULL,

    -- STRUCTURAL FACTS ONLY, REBUILT FROM AN ALLOWLIST.
    --
    -- The in-memory details map carries opening and closing balances, credit and debit totals, and
    -- for COLUMN_AMBIGUITY the raw cell value that was ambiguous. That is statement content, and
    -- V59 is explicit that duplicating any part of a bank statement into a telemetry table is easy
    -- to add and very hard to walk back. ImportVerificationRecorder rebuilds this document from a
    -- named allowlist of structural keys rather than stripping the monetary ones out, so a detail
    -- key added by a future rule is absent here by construction rather than by remembering.
    --
    -- TEXT, matching layout_metadata_json / unanchored_reasons_json. Nothing queries into these
    -- documents; one storage convention for parser JSON beats two.
    details_json        TEXT,

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT import_verification_findings_one_owner CHECK (
        (analysis_session_id IS NOT NULL AND import_job_id IS NULL)
     OR (analysis_session_id IS NULL AND import_job_id IS NOT NULL))
);

-- Both access patterns are "every finding for this upload attempt", by whichever owner the caller
-- holds. Partial so each index stays proportional to the path that populates it.
CREATE INDEX idx_import_verification_findings_analysis
    ON import_verification_findings (analysis_session_id, section_index)
    WHERE analysis_session_id IS NOT NULL;

CREATE INDEX idx_import_verification_findings_job
    ON import_verification_findings (import_job_id, section_index)
    WHERE import_job_id IS NOT NULL;

COMMENT ON TABLE import_verification_findings IS
    'One verification rule''s outcome for one staged section, kept instead of discarded with the '
    'staging response. Owned by an analysis session (synchronous upload) or an import job (async).';
COMMENT ON COLUMN import_verification_findings.details_json IS
    'Structural facts only, rebuilt from an allowlist -- never balances, totals or raw cell values.';

-- ---------------------------------------------------------------------------------------------
-- 3. THE JOIN
-- ---------------------------------------------------------------------------------------------
--
-- The charter says the three tables "share correlation IDs and nothing joins them". Half of that
-- was aspirational: statement_analysis_sessions has never carried a correlation id, and there has
-- never been a key connecting an upload's evidence row to the staging session that produced it.
-- These two columns are what make a single view a query rather than a reconciliation.
--
-- import_session_id is the load-bearing one. merchant_learning_events.source_import_session_id
-- already exists (V63), so once the analysis row names its staging session, "which merchants did
-- this import teach" becomes one join instead of a guess.
ALTER TABLE statement_analysis_sessions ADD COLUMN import_session_id UUID;

-- Deliberately NOT a foreign key. Import sessions are deleted after their 48-hour TTL, and an
-- analysis row must outlive that -- it is the permanent evidence of an upload, while the session
-- is transient review state. ON DELETE SET NULL would erase the link and leave no record that
-- there had been one; keeping the id after the session is gone at least says which session it was.
-- Same reasoning V59 gives for having no foreign key to users.
COMMENT ON COLUMN statement_analysis_sessions.import_session_id IS
    'The staging session this upload produced, when it produced one. No FK on purpose: sessions '
    'expire after 48 hours and the evidence row must outlive them.';

-- The id the logs, the audit rows and the Sentry events for this upload all carry. Nothing else
-- can take an operator from a row in this table to the log lines that produced it.
ALTER TABLE statement_analysis_sessions ADD COLUMN correlation_id VARCHAR(64);

COMMENT ON COLUMN statement_analysis_sessions.correlation_id IS
    'request-/worker-/scheduler- prefixed id shared with the logs, audit rows and Sentry events '
    'from the same upload. See WorkerObservability''s correlation convention.';

-- Backs the trace view's "the analysis for this staging session" lookup. Partial: rows written
-- before this migration, and every admin analysis, have no session.
CREATE INDEX idx_analysis_import_session
    ON statement_analysis_sessions (import_session_id)
    WHERE import_session_id IS NOT NULL;
