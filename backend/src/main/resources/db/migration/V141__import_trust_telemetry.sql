-- Phase 0 of the import trust model: persist the extraction evidence an import already computes.
--
-- Today every one of these facts is calculated during staging, attached to the staging DTO, shown
-- to the user, and then discarded. ImportVerificationRecorder.recordForJob(importJobId, ...) was
-- written to persist the per-rule findings and is never called -- only recordForAnalysis is wired,
-- which keys on analysis_session_id, so the admin analysis path has history and the real user
-- import path has none.
--
-- The consequence is that "how often would a trust gate fire?" is currently unanswerable. Any
-- threshold chosen now would be a guess. This migration and its wiring change no behaviour: nothing
-- is gated, nothing is blocked, no user-visible outcome moves. It only makes the evidence
-- reconstructable after the fact.
--
-- Nullable with no default, deliberately. NULL means "this import predates telemetry", which has to
-- stay distinguishable from "this import was clean" -- a DEFAULT would silently assert the second
-- about every historical row.

ALTER TABLE import_jobs
    -- The derived signal ImportReliabilityStatusDeriver already computes: CLEAN,
    -- REVIEW_RECOMMENDED or NEEDS_ATTENTION. Stored as its name, matching how every other enum in
    -- this schema is persisted (VARCHAR + @Enumerated(STRING), never a native Postgres enum).
    ADD COLUMN reliability_status VARCHAR(24),

    -- NATIVE / OCR / NATIVE_PLUS_OCR. Provenance, not a defect: OCR text is noisier, so knowing
    -- which extraction path produced a statement is what lets a later analysis ask whether a
    -- candidate threshold behaves differently on scanned documents than on native ones.
    ADD COLUMN text_source VARCHAR(24),

    -- The header had to be rebuilt heuristically rather than read. One of the three inputs to
    -- NEEDS_ATTENTION, and the only one that produces no VerificationFinding of its own -- so
    -- without this column it is the one trust signal that could never be measured historically.
    ADD COLUMN header_reconstruction_uncertain BOOLEAN,

    -- The deploy that parsed this statement (RAILWAY_GIT_COMMIT_SHA). Its job is loop prevention --
    -- never reprocess a statement under the build that already failed it -- rather than attributing
    -- a fix to the statements it repaired. A deploy SHA changes on every commit including unrelated
    -- ones, so it cannot answer "which statements would parser v42 now parse"; re-running candidates
    -- answers that. Recorded now because it cannot be backfilled later.
    ADD COLUMN parser_version VARCHAR(40),

    -- Coarse counts, duplicated from import_verification_findings on purpose.
    --
    -- The per-rule detail still lives there and stays the source of truth. These exist so the
    -- questions asked most often -- "what share of imports had any failing check?" -- are a GROUP BY
    -- on one table rather than a join plus JSON parsing of a TEXT column that, by its own comment,
    -- nothing queries into.
    ADD COLUMN verification_findings_count INT,
    ADD COLUMN verification_failed_count INT,
    ADD COLUMN verification_warning_count INT;

COMMENT ON COLUMN import_jobs.reliability_status IS
    'CLEAN / REVIEW_RECOMMENDED / NEEDS_ATTENTION, as computed at staging. Advisory: nothing gates '
    'on it. NULL means the import predates trust telemetry, which is not the same as CLEAN.';

COMMENT ON COLUMN import_jobs.parser_version IS
    'Deploy SHA that parsed this statement. For loop prevention on reprocess -- never retry a '
    'statement under the build that already failed it. Not a fix-attribution key.';

COMMENT ON COLUMN import_jobs.verification_failed_count IS
    'How many verification rules returned FAILED. Denormalised from import_verification_findings so '
    'the common aggregate question needs no join and no JSON parsing; that table stays the detail '
    'source of truth.';
