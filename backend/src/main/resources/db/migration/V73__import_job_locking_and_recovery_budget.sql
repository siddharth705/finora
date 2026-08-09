-- Two defects from the 2026-08-08 repo-wide bug hunt, both in the import queue's state machine and
-- both needing a column. See docs/engineering/reviews/2026-08-08-repo-wide-bug-hunt.md, BH-020 and
-- BH-002.
--
-- version -- optimistic locking (BH-020)
-- --------------------------------------
-- Two writers touch an import job concurrently BY DESIGN: the worker, through
-- ImportJobStore.update in its own REQUIRES_NEW transaction, and the job's owner, through
-- ImportJobService.cancel in the request's transaction. Both are read-modify-write and neither
-- could see the other, so last write won.
--
-- That is the mechanism behind the hunt's most severe finding. A cancel landing between the
-- worker's last abortIfCancelled and its complete() made complete() throw, the worker's general
-- handler called recordFailure, and the CANCELLED job went straight back on the queue -- was
-- re-claimed, ran to the end, and handed the user the staged session they had pressed Stop on.
-- The application-side fix (recordFailure refusing to move a terminal job) is the one that closes
-- that specific path; this column is what makes the conflict detectable at all rather than
-- surfacing as an exception thrown from business logic.
--
-- Every other concurrently-written entity here already carries it -- accounts, transactions,
-- budgets, refresh_tokens, password_change_sessions (V48), statement_imports -- and
-- GlobalExceptionHandler.handleOptimisticLock already answers 409 CONFLICT for the resulting
-- exception, so no endpoint changes.
--
-- recovery_count -- a ceiling on recovery (BH-002)
-- ------------------------------------------------
-- ImportJob.markClaimed increments attempt_count and ImportJob.returnToQueue used to decrement it,
-- so the two exactly cancelled. A job whose parse reliably KILLS its worker -- an OOM on a large
-- PDF, a stack overflow in the table locator -- cycled claim -> crash -> recover -> claim for ever
-- at a net attempt count of zero. It never reached MAX_ATTEMPTS, never dead-lettered, never
-- appeared in the admin queue, and consumed one of ten claim slots on every single pass. Verified
-- by driving the entity through twelve crash/recover cycles: attempt_count 0, MAX_ATTEMPTS 5.
--
-- Counting recoveries separately is what stops the two counters cancelling. It is a SEPARATE
-- column rather than "stop decrementing" because the original reasoning is sound and worth
-- keeping: an attempt is evidence about the DOCUMENT (the parse ran and threw), a recovery is
-- evidence about the WORKER (something killed the process), and a deploy should not dead-letter
-- perfectly good work. ImportJob.MAX_RECOVERIES bounds it at 3 -- enough to absorb a deploy, a
-- restart and one genuine crash; a fourth says the job is the common factor.

ALTER TABLE import_jobs ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE import_jobs ADD COLUMN recovery_count INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN import_jobs.recovery_count IS
    'How many times recovery returned this job to the queue after a worker abandoned it. Separate '
    'from attempt_count because the two count different evidence -- and because, sharing one '
    'counter, they cancelled and the job never dead-lettered.';
