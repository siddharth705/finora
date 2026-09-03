-- HELD_FOR_REVIEW: a dead-lettered import whose failure nothing recognized, held for admin triage
-- instead of being shown to the user as a bare failure they can do nothing about.
--
-- Entered only when ExceptionClassifier returned RETRY_ONCE_THEN_ALERT *and* the job dead-lettered
-- -- a genuinely unclassified exception, which in practice means a parser gap on a statement layout
-- this codebase has not seen. Known ErrorCode failures (wrong password, unsupported file type) and
-- exhausted transient-infrastructure retries keep landing in plain FAILED with their existing
-- messages: the user can act on the first, and the second is not a parser gap.

ALTER TABLE import_jobs
    DROP CONSTRAINT import_jobs_status_valid;

ALTER TABLE import_jobs
    ADD CONSTRAINT import_jobs_status_valid CHECK (status IN (
        'QUEUED','PARSING','ANALYZING','DEDUPING','IMPORTING','LEARNING',
        'COMPLETED','FAILED','HELD_FOR_REVIEW','CANCELLED'));

-- WHY THIS INDEX HAS TO BE REBUILT, NOT JUST LEFT ALONE
-- -----------------------------------------------------
-- idx_import_jobs_live_content (V74) enumerates the terminal statuses literally, as the set a live
-- job is NOT in. ImportJob.Status.TERMINAL now contains HELD_FOR_REVIEW; this predicate did not,
-- and the two disagreeing is not cosmetic.
--
-- ImportJobService.enqueueStoredUpload looks for an existing job with StatusNotIn(TERMINAL) before
-- enqueuing, and returns it if there is one. With HELD_FOR_REVIEW in TERMINAL that pre-check treats
-- a held job as finished and falls through to the INSERT -- which the stale index would then reject
-- as a duplicate live job. The user is told "no action needed from you right now", re-uploads the
-- same statement anyway (people do), and gets a 409 for their trouble.
--
-- So the predicate follows TERMINAL. Re-uploading a statement whose earlier import is held is
-- legitimate for the same reason V74 gives for COMPLETED/FAILED/CANCELLED: what must not exist
-- twice is two jobs racing to stage the same bytes, and a held job is not racing anything.
--
-- The reverse direction -- reprocessing a held job while a newer live job holds the same
-- (user_id, content_hash) -- would violate this index on the UPDATE. AdminHeldImportService checks
-- for that and answers 409 rather than letting a constraint violation surface as a 500.
DROP INDEX idx_import_jobs_live_content;

CREATE UNIQUE INDEX idx_import_jobs_live_content
    ON import_jobs (user_id, content_hash)
    WHERE content_hash IS NOT NULL
      AND status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'HELD_FOR_REVIEW');

COMMENT ON INDEX idx_import_jobs_live_content IS
    'One live job per user per document. Terminal rows are excluded so re-uploading a statement '
    'whose earlier import finished, failed, was cancelled, or is held for review stays possible. '
    'This predicate must track ImportJob.Status.TERMINAL exactly -- see V132 for what breaks when '
    'they drift.';

-- Set by holdForReview() and never cleared, including by a reprocess. The status cannot answer
-- "was this ever held": by the time a reprocessed job completes it is COMPLETED and the hold is
-- gone. The completion notification needs the answer, because only a user who was told we were
-- running additional checks is owed a follow-up -- a first-time success notifies nobody.
--
-- DEFAULT FALSE backfills every existing row correctly: no job predating this migration was ever
-- held, so none of them is owed a notification.
ALTER TABLE import_jobs
    ADD COLUMN was_held_for_review BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN import_jobs.was_held_for_review IS
    'True if this job was ever HELD_FOR_REVIEW. Never cleared -- it survives the reprocess, which '
    'is the point: it is what tells the success path to notify a user who was asked to wait.';

-- Backs the admin triage queue: held jobs, oldest first, so the longest-waiting user is at the top.
-- Partial for the same reason idx_import_jobs_claimable is -- the overwhelming majority of rows on
-- this table are COMPLETED and will never appear in this queue.
CREATE INDEX idx_import_jobs_held ON import_jobs (created_at) WHERE status = 'HELD_FOR_REVIEW';

COMMENT ON COLUMN import_jobs.status IS
    'Combined status and stage -- one state machine, so the two can never disagree. '
    'HELD_FOR_REVIEW is terminal for the worker but distinct from FAILED: the failure was '
    'unclassified (a likely parser gap), the stored statement object is retained, and an admin can '
    'reprocess it once the parser is fixed.';
