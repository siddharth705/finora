-- Phase 2 of docs/engineering/statement-storage-migration.md: where a statement's bytes live.
--
-- Both columns are NULLABLE and file_content is untouched. This is a dual-write step, not a move:
-- a new upload writes its bytes to object storage AND still fills file_content, so the change is
-- reversible by unsetting app.statement-storage.provider. Existing rows keep NULL here and are
-- read from file_content exactly as before; Phase 3 backfills them, Phase 4 drops the column.
--
-- content_hash is the document's IDENTITY (hex SHA-256, always 64 chars). object_key is where the
-- configured provider happens to have put it, and is deliberately a separate column: bucket layout
-- is the thing most likely to change later, and re-laying it out must be a rewrite of keys rather
-- than a rewrite of how every row identifies its document. See ContentAddress.
--
-- Not UNIQUE, and that is the point. Content-addressing means many rows legitimately share one
-- hash: one row per account section of a composite statement, and another for every re-import.
-- Deduplicating those onto a single stored object is the problem this migration exists to solve,
-- so a uniqueness constraint here would reject exactly the case it is meant to support.
ALTER TABLE statement_imports ADD COLUMN content_hash VARCHAR(64);
ALTER TABLE statement_imports ADD COLUMN object_key   VARCHAR(512);

ALTER TABLE import_sessions ADD COLUMN content_hash VARCHAR(64);
ALTER TABLE import_sessions ADD COLUMN object_key   VARCHAR(512);

-- Phase 3's backfill walks rows that have no address yet, and the future sweep asks "does any row
-- still reference this hash". Both are hash lookups over a growing table.
CREATE INDEX idx_statement_imports_content_hash ON statement_imports (content_hash);
CREATE INDEX idx_import_sessions_content_hash   ON import_sessions (content_hash);

COMMENT ON COLUMN statement_imports.content_hash IS
    'Hex SHA-256 of the original file -- the document identity. NULL until Phase 3 backfills pre-V54 rows.';
COMMENT ON COLUMN statement_imports.object_key IS
    'Provider-internal storage key for content_hash. Layout detail, not identity -- see ContentAddress.';
