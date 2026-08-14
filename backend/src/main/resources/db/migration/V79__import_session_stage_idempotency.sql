-- Mirrors V74__import_job_submission_idempotency.sql's reasoning, applied to the synchronous
-- stage path's import_sessions instead of the asynchronous queue's import_jobs: the same document
-- submitted twice via POST /csv/stage or /pdf/stage -- a double-clicked upload button, or a client
-- retrying a request whose response was lost -- becomes two staged sessions, and confirming both
-- would import the statement twice. V74 already closed this for the async job queue; this closes
-- the equivalent gap on the synchronous path, which had no protection at all.
--
-- WHY A PARTIAL INDEX RATHER THAN AN APPLICATION CHECK
-- ----------------------------------------------------
-- ImportService.parseAndStageWithSession/parseAndStagePdfWithSession look for an existing live
-- session by content hash BEFORE parsing and return it if there is one -- same reasoning
-- ImportJobService.accept already gives for its own app-level check, and the reason this one runs
-- ahead of the parse rather than only at session-creation time: the expensive work is the parse
-- itself, not the row it produces. But a check is a read followed by a write, and two genuinely
-- simultaneous uploads can both read "not present" before either writes. The database decides
-- then; the loser's INSERT hits this constraint and GlobalExceptionHandler already answers a
-- DataIntegrityViolationException as 409, same as V74's own loser case.
--
-- SCOPED TO STAGED, DELIBERATELY
-- ------------------------------
-- CONFIRMED is this table's only other status. What must not exist twice is two STAGED sessions
-- racing to hold the same bytes; a session that has already moved to CONFIRMED cannot be
-- double-confirmed regardless (ImportSessionService.claimForConfirmation's own atomic UPDATE
-- already makes that impossible), so it carries no risk this index needs to guard against.
--
-- content_hash IS NOT NULL because it is nullable while object storage remains optional (see
-- StatementContentService) -- a row without one carries no identity to deduplicate on. Same
-- caveat V74 states for import_jobs.
--
-- EXPIRY IS AN APPLICATION-LEVEL CONCERN, NOT PART OF THIS INDEX
-- ----------------------------------------------------------------
-- A partial index predicate must be immutable, so it cannot reference now(). A STAGED session that
-- has expired but has not yet been swept (ImportSessionService.sweepExpiredSessions runs on a
-- schedule, not instantly) still counts as "live" by this constraint alone -- which is why
-- ImportSessionService.findLiveSessionByContentHash deletes an expired match on the way past
-- rather than treating it as a block. A genuinely new upload of a statement whose earlier session
-- merely expired must succeed, not fail with a false duplicate.

-- Pre-existing duplicates would make CREATE UNIQUE INDEX fail at startup. Keeps the newest STAGED
-- session per (user, content hash) and deletes the rest: a disposable, never-confirmed staging
-- artifact carries no history value the way a terminal import_jobs row does (nobody reviews old
-- staged-but-abandoned sessions the way an admin queue reviews failed jobs), so deleting the
-- superseded rows outright is the right analogue to V74's cancel-don't-delete choice, not a
-- departure from it.
WITH ranked AS (
    SELECT id,
           ROW_NUMBER() OVER (PARTITION BY user_id, content_hash ORDER BY created_at DESC, id) AS rn
      FROM import_sessions
     WHERE content_hash IS NOT NULL
       AND status = 'STAGED'
)
DELETE FROM import_sessions
 WHERE id IN (SELECT id FROM ranked WHERE rn > 1);

CREATE UNIQUE INDEX idx_import_sessions_live_content
    ON import_sessions (user_id, content_hash)
    WHERE content_hash IS NOT NULL
      AND status = 'STAGED';

COMMENT ON INDEX idx_import_sessions_live_content IS
    'One live (STAGED) session per user per document. An expired-but-unswept match is an '
    'application-level concern (ImportSessionService.findLiveSessionByContentHash deletes it), '
    'not something this index''s predicate can express.';
