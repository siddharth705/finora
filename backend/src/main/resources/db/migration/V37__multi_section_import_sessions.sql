-- Multi-account PDF import support (e.g. HSBC's "Composite Statement", which bundles a
-- savings-account section and a credit-card section in one file). A single import session can
-- now stage either one account (session_kind = SINGLE_ACCOUNT, unchanged behavior, the two
-- existing JSON columns populated exactly as before) or several (session_kind = MULTI_ACCOUNT,
-- sections_json populated instead, the two single-account columns left null). See
-- ImportSession.java and ImportSessionService.java for the read/write split between the two kinds.
ALTER TABLE import_sessions ALTER COLUMN staged_rows_json DROP NOT NULL;
ALTER TABLE import_sessions ALTER COLUMN detected_account_json DROP NOT NULL;
ALTER TABLE import_sessions ADD COLUMN sections_json TEXT;
ALTER TABLE import_sessions ADD COLUMN session_kind VARCHAR(20) NOT NULL DEFAULT 'SINGLE_ACCOUNT';

-- Which section (0-based) of a multi-account PDF a given StatementImport came from -- null for
-- every existing row (all single-account so far) and for any future single-account confirm.
-- Required for StatementImportService.reimport() to replay the CORRECT section: without this,
-- reimporting a StatementImport that came from section 1+ of an HSBC-style composite PDF would
-- silently restage section 0's transactions against the wrong account. See
-- ImportService.parseAndStageAnyFormat()'s section-aware branch.
ALTER TABLE statement_imports ADD COLUMN source_section_index INTEGER;
