-- BH-029 from docs/engineering/reviews/2026-08-08-repo-wide-bug-hunt.md: the parser a queued job
-- runs is decided by its filename, twice, minutes apart.
--
-- WHAT WAS ACTUALLY WRONG
-- ----------------------
-- ImportJobService.formatOf(fileName) was called at upload, to decide what StatementUpload
-- validated the bytes against, and again in ImportJobWorker.stage(), to decide which parser runs.
-- Both read import_jobs.file_name, so they agreed -- but by construction rather than by record.
-- The format was never a fact anywhere; it was a function re-evaluated against a stored string,
-- and the agreement lasted exactly as long as nobody touched that string or that function.
--
-- statement_imports.source_format (V36) exists for the same reason and says so in its own comment:
-- reimport() used to re-infer the format from the filename, and routing a PDF's bytes through
-- CsvParser either throws or silently produces garbage rows. That lesson was applied to the
-- confirmed import and not to the job that produced it.
--
-- WHY NOT NULL WITH A BACKFILL RATHER THAN NULLABLE
-- ------------------------------------------------
-- A nullable column would mean the worker keeps a filename fallback for rows that predate this
-- migration, which leaves the defect in place under a branch nobody exercises -- the worst place
-- for it. Existing rows are backfilled with the same rule the code used, so the column is exactly
-- as correct as the behaviour it replaces and never less. Live rows are few in any case: the async
-- queue is opt-in and off by default (app.import.queue.enabled).
ALTER TABLE import_jobs ADD COLUMN source_format VARCHAR(10);

-- The literal expression ImportJobService.formatOf encodes: PDF iff the lowercased name ends
-- .pdf, CSV otherwise -- including for a NULL name, which formatOf also answers CSV.
UPDATE import_jobs
   SET source_format = CASE WHEN lower(file_name) LIKE '%.pdf' THEN 'PDF' ELSE 'CSV' END
 WHERE source_format IS NULL;

ALTER TABLE import_jobs ALTER COLUMN source_format SET NOT NULL;

-- VARCHAR(10) to match statement_imports.source_format (V36) exactly, so the two columns holding
-- the same vocabulary cannot diverge in width. Same reasoning V68 gives for its own copy.
COMMENT ON COLUMN import_jobs.source_format IS
    'Which parser this job runs, decided once at upload and never re-derived. BH-029: it used to '
    'be recomputed from file_name both at upload validation and in the worker.';
