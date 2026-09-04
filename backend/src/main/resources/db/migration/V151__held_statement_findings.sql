-- What an engineer found and fixed, recorded once a parser gap is diagnosed. Two plain columns
-- rather than folding this into engineer_notes -- see HeldStatementService.addNotes's own doc for
-- why that column stays free text (structuring it before anyone had written any note would have
-- been inventing a schema for work nobody had done). Plan 3 is that work: root_cause and
-- fix_reference are the two facts a parser-bug write-up always needs, kept separate from
-- engineer_notes so a re-run's own event log has somewhere machine-readable to point back to.
--
-- Both nullable: most holds never need an engineer, and never get one.
ALTER TABLE held_statements ADD COLUMN root_cause TEXT;
ALTER TABLE held_statements ADD COLUMN fix_reference VARCHAR(200);

COMMENT ON COLUMN held_statements.root_cause IS
    'What the engineer found wrong with the parser, if anything. Free text, set once diagnosed.';
COMMENT ON COLUMN held_statements.fix_reference IS
    'Where the fix landed -- a PR number or URL, at the engineer''s discretion. Free text, not validated.';

-- Optimistic locking. held_statements is mutated by several independent actors on the same row --
-- assign, investigate, notes, findings, approve, reject, and (Plan 3) rerun-parser -- and until
-- now carried no @Version, unlike every other concurrently-written entity in this codebase (see
-- ImportJob.version's own doc, BH-001). Without it, two concurrent admin actions on the same hold
-- resolve as a silent last-write-wins UPDATE: no exception, no log line, just whichever commit
-- landed second overwriting the first admin's change. GlobalExceptionHandler.handleOptimisticLock
-- already answers 409 for the resulting ObjectOptimisticLockingFailureException -- adding the
-- column is the only new thing this needs.
ALTER TABLE held_statements ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
