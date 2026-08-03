-- Phase 4 step 12: a capability backlog with real frequency counts, rather than a table of
-- anecdotes about whichever documents someone happened to debug by hand.
--
-- Deliberately a SUMMARY, not the rows. An unparseable row is a line of somebody's bank statement:
-- persisting the raw values to count them would put customer statement content into a table whose
-- entire purpose is engineering metrics, read by admins, retained indefinitely -- exactly the kind
-- of place customer data ends up when nobody decided to put it there. What a capability backlog
-- actually needs is "how often does this SHAPE of row fail, and why", and that is a histogram:
--
--   {"reasons": {"No date value in any recognized date column": 61},
--    "columnSignatures": {"date|narration|amount": 61},
--    "total": 61}
--
-- Reasons are engine-authored strings (TransactionNormalizer.explainFailure) and column signatures
-- are header names, both of which are statement furniture rather than anybody's data.
ALTER TABLE statement_imports ADD COLUMN IF NOT EXISTS unparseable_summary_json TEXT;

-- The same summary on the session, so it survives from staging (where unparseable rows are
-- computed) to confirm (where the statement_imports row is created), the same route
-- layout_metadata_json / layout_fingerprint / activated_capabilities_json already take.
ALTER TABLE import_sessions ADD COLUMN IF NOT EXISTS unparseable_summary_json TEXT;
