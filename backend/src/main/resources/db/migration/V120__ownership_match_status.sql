-- docs/proposals/account-ownership-intelligence-proposal.md §3.2 -- V1 of Ownership Intelligence.
-- Compares a statement's extracted account-holder name against the confirming user's Finora profile
-- name at confirm time, and records the outcome for a non-blocking warning and for later
-- debugging/analytics. Never surfaced to end users directly (see the design doc's own note on this)
-- -- these are audit/debugging columns, not user-facing data.

-- Snapshot of what PdfMetadataExtractor saw for this statement's account holder at confirm time.
-- Null on any confirm path with no ImportSession to read it from (StatementImportService
-- .confirmReimport's byte-array replay path) -- same "best-effort, left null with no session"
-- discipline layout_metadata_json/layout_fingerprint already follow on this table.
ALTER TABLE statement_imports ADD COLUMN extracted_holder_name VARCHAR(255) NULL;

-- NAME_MATCH / NAME_MISMATCH / NO_HOLDER_FOUND / SKIPPED_EXISTING_ACCOUNT (StatementImport
-- .OwnershipMatchStatus). No default and no legacy/unknown member, unlike
-- balance_application_mode above: the design doc's own decision is not to backfill or guess at
-- this for historical rows -- null on an old row simply means "nothing to check", which the
-- comparison logic already treats as the same case as a statement with no extractable holder name.
ALTER TABLE statement_imports ADD COLUMN ownership_match_status VARCHAR(30) NULL;

-- Whether the user clicked "Continue Import" after seeing the non-blocking warning. Null whenever
-- ownership_match_status isn't NAME_MISMATCH -- there was no warning to confirm past.
ALTER TABLE statement_imports ADD COLUMN user_confirmed_continue BOOLEAN NULL;
