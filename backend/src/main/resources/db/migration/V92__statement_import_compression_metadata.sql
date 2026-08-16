-- Storage review: statement bytes are now compressed (GZIP) before being written to R2 at confirm
-- time (StatementContentService/ImportService.persistSection). These four columns replace magic-
-- byte sniffing on read with explicit metadata -- see StoredStatement/StatementContentService for
-- how compression_type drives decompression, and ContentAddress for why content_hash stays the
-- hash of the ORIGINAL, uncompressed bytes (document identity/dedup/audit is unaffected by this).
--
-- Nullable except compression_type: original_size/stored_size/original_mime_type are best-effort
-- metrics, not invariants existing rows need to satisfy, matching V76's precedent of not requiring
-- a backfill. compression_type defaults to 'NONE' for every existing row -- correct for both a
-- legacy BYTEA-only row (nothing was ever compressed) and any row already sitting in R2 from before
-- this change shipped (also uncompressed, since compression did not exist yet). Only rows written
-- going forward, once StatementContentService actually compresses, record 'GZIP'.
ALTER TABLE statement_imports ADD COLUMN original_size BIGINT;
ALTER TABLE statement_imports ADD COLUMN stored_size BIGINT;
ALTER TABLE statement_imports ADD COLUMN original_mime_type VARCHAR(100);
ALTER TABLE statement_imports ADD COLUMN compression_type VARCHAR(10) NOT NULL DEFAULT 'NONE';
ALTER TABLE statement_imports ADD CONSTRAINT statement_imports_compression_type_valid
    CHECK (compression_type IN ('NONE', 'GZIP'));
