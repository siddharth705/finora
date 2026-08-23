-- Security review: statement bytes in object storage (R2) were compressed but never encrypted --
-- see docs/security/secrets-and-iam-audit.md sec 2.2/3 on the R2 credential's blast radius. This
-- column records which EncryptionService key id (see KeyProvider) a row's object-storage bytes
-- were encrypted under, the same key-id-alongside-ciphertext pattern GmailConnection already uses
-- for OAuth refresh tokens.
--
-- Nullable, same reasoning as compression_type's predecessor (V92) and content_hash (V54): every
-- row written before this shipped has unencrypted object-storage bytes (or none at all, for a
-- legacy database-only row), and StatementContentService.read decides whether to decrypt by
-- whether this column is set, not by inspecting the bytes -- the same explicit-metadata-over-
-- sniffing approach compression_type already established. Nothing is backfilled: an existing R2
-- object stays exactly as it is on disk; only rows stored going forward, once this ships, are
-- encrypted and carry a key id.
ALTER TABLE statement_imports ADD COLUMN encryption_key_id VARCHAR(50);
ALTER TABLE import_jobs ADD COLUMN encryption_key_id VARCHAR(50);
