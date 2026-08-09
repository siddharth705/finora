-- BH-019 from docs/engineering/reviews/2026-08-08-repo-wide-bug-hunt.md: the same document
-- submitted twice becomes two jobs.
--
-- WHAT V67 DID AND DID NOT COVER
-- ------------------------------
-- V67 made REPLAY of one job row safe: statement_imports.import_job_id is unique, so a job
-- returned to the queue after confirming cannot import the same statement twice. Neither of its
-- constraints says anything about the same bytes being SUBMITTED twice, which is a different
-- event with a different cause -- a double-clicked upload button, or a client retrying a request
-- whose 202 was lost. Two POSTs, two job rows, two staged sessions, and if the user confirms both
-- the statement lands twice.
--
-- WHY A PARTIAL INDEX RATHER THAN AN APPLICATION CHECK
-- ----------------------------------------------------
-- ImportJobService.accept looks for a live job before enqueuing and returns it if there is one,
-- which handles the ordinary case -- a second click arriving after the first has committed -- and
-- returns the SAME jobId, so the client polls the work that is already happening rather than
-- getting an error. But a check is a read followed by a write, and two genuinely simultaneous
-- uploads can both read "not present" before either writes. That is the same reasoning V67's own
-- comment gives for choosing constraints over checks, and the same answer: the database decides.
-- The loser gets a constraint violation, which GlobalExceptionHandler already answers as 409.
--
-- SCOPED TO LIVE JOBS, DELIBERATELY
-- ---------------------------------
-- Terminal rows are excluded. Re-uploading a statement whose earlier import COMPLETED, FAILED or
-- was CANCELLED is a legitimate thing to do -- re-importing after fixing an account mapping, or
-- retrying something that failed -- and a unique index over all history would make the queue
-- refuse it for ever. What must not exist twice is two jobs racing to stage the same bytes.
--
-- content_hash IS NOT NULL because it is nullable while object storage remains optional; a row
-- without one carries no identity to deduplicate on.

-- Pre-existing duplicates would make CREATE UNIQUE INDEX fail at startup, which on a Flyway
-- migration means the deployment does not come up. The async queue is opt-in and off by default
-- (app.import.queue.enabled), so in practice this finds nothing -- but "in practice" is not a
-- thing to bet a deploy on. Older duplicates are cancelled rather than deleted: a cancelled job is
-- part of the user's history and the newest submission is the one they are waiting on.
WITH ranked AS (
    SELECT id,
           ROW_NUMBER() OVER (PARTITION BY user_id, content_hash ORDER BY created_at DESC, id) AS rn
      FROM import_jobs
     WHERE content_hash IS NOT NULL
       AND status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')
)
UPDATE import_jobs
   SET status      = 'CANCELLED',
       finished_at = now(),
       last_error  = 'Superseded by a later submission of the same document (V74).'
 WHERE id IN (SELECT id FROM ranked WHERE rn > 1);

CREATE UNIQUE INDEX idx_import_jobs_live_content
    ON import_jobs (user_id, content_hash)
    WHERE content_hash IS NOT NULL
      AND status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED');

COMMENT ON INDEX idx_import_jobs_live_content IS
    'One live job per user per document. Terminal rows are excluded so re-uploading a statement '
    'whose earlier import finished, failed or was cancelled stays possible.';
