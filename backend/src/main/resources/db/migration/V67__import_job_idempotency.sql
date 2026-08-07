-- Phase 2 of docs/engineering/enterprise-scale-milestone-design.md §8.3: make import replay safe.
--
-- THE BLOCKER THIS REMOVES
-- ------------------------
-- At-least-once delivery means every consumer must tolerate replay, and the import worker does not.
-- A worker that dies after confirming an import but before marking its job complete leaves the job
-- in flight; recovery returns it to the queue and the next worker imports the same statement again.
-- The user gets every transaction twice, silently, and nothing in the system objects.
--
-- Today that is survivable only because exactly one worker runs and confirmation still happens on
-- the request thread. It is the reason the design says idempotency is "a prerequisite for scaling
-- workers out, not a follow-up".
--
-- TWO CONSTRAINTS, GUARDING TWO DIFFERENT FAILURES
-- ------------------------------------------------
-- 1. statement_imports.import_job_id UNIQUE -- one job produces at most one import.
--    This is the one that makes retry safe. A replay creates a NEW StatementImport, so a key scoped
--    within an import (below) cannot see the duplication; only a key tying the import back to the
--    job that caused it can. The database rejects the second insert, and the worker treats that as
--    "already done" rather than as an error.
--
-- 2. transactions (statement_import_id, row_ordinal) UNIQUE -- one import produces each row once.
--    Defence in depth against a different failure: a retry INSIDE a single confirm, or a future
--    batching change that replays part of a list. Constraint 1 would not catch that, because the
--    statement import is the same one.
--
-- Both are database constraints rather than application checks, deliberately. An application check
-- is a read followed by a write, and two workers can both read "not present" before either writes.

ALTER TABLE statement_imports ADD COLUMN import_job_id UUID;

-- Partial: only jobs claim uniqueness. Every import created by the synchronous path has NULL here
-- and must stay unconstrained -- a plain UNIQUE would still permit multiple NULLs in Postgres, but
-- stating the predicate makes the intent reviewable and keeps the index proportional to the async
-- path rather than to every import ever run.
CREATE UNIQUE INDEX idx_statement_imports_job
    ON statement_imports (import_job_id)
    WHERE import_job_id IS NOT NULL;

COMMENT ON COLUMN statement_imports.import_job_id IS
    'The async job that produced this import. UNIQUE: a replayed job cannot import twice. NULL for '
    'the synchronous path, which has no job.';

-- Position within its statement, assigned at insert. Nullable because every transaction that
-- predates this migration has no ordinal, and because manually-created transactions have no
-- statement at all.
ALTER TABLE transactions ADD COLUMN row_ordinal INT;

-- Partial for the same reason: manual transactions carry NULL for both columns and are not part of
-- this guarantee.
CREATE UNIQUE INDEX idx_transactions_import_row
    ON transactions (statement_import_id, row_ordinal)
    WHERE statement_import_id IS NOT NULL AND row_ordinal IS NOT NULL;

COMMENT ON COLUMN transactions.row_ordinal IS
    'Position within its statement import, 0-based. With statement_import_id this is the natural '
    'key that makes a replayed insert a constraint violation rather than a duplicate row.';
