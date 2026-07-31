-- Records each statement's original format explicitly, rather than reimport() inferring it from
-- the stored filename's extension (a real, if narrow, fragility -- nothing stops a re-upload
-- with a missing or mismatched extension). Every existing row predates PDF support and was
-- necessarily CSV, so backfilling 'CSV' for all of them is correct, not a guess.
ALTER TABLE statement_imports ADD COLUMN source_format VARCHAR(10) NOT NULL DEFAULT 'CSV';
