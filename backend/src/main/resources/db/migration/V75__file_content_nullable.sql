-- BH-025/BH-046: stop dual-writing statement bytes into BYTEA once an object-storage address
-- exists (docs/engineering/statement-storage-migration.md §5.0).
--
-- Both file_content columns were NOT NULL because Phase 2 was an unconditional dual write --
-- every row got its bytes in object storage (when configured) AND in the database, "temporary"
-- until a Phase 3 backfill and Phase 4 column drop. BH-046 found neither survived: Phase 3 was
-- deleted for having nothing to migrate (no production statements existed), and Phase 4 never
-- got a trigger, so the "temporary" duplication had quietly become the permanent state. BH-025
-- separately found what that permanence costs: confirmMultiSection() persists one
-- statement_imports row per detected account section, all sharing the same file bytes, so a
-- 3-section 9 MB statement wrote 27 MB of BYTEA on top of the one already-deduplicated
-- content-addressed object.
--
-- The application now writes file_content only when statementContentService.store() returns
-- empty (no provider configured, preserving today's behaviour exactly) -- see
-- ImportService.persistSection and ImportSessionService.storeContent. When a content address IS
-- recorded, file_content is left null: the object is the only copy. Existing rows are untouched;
-- this migration only relaxes the constraint so new rows can take the null branch.
--
-- The new invariant, mirrored by StoredStatement/StatementContentService.read: file_content is
-- null if and only if object_key (equivalently content_hash) is set.
ALTER TABLE statement_imports ALTER COLUMN file_content DROP NOT NULL;
ALTER TABLE import_sessions   ALTER COLUMN file_content DROP NOT NULL;

ALTER TABLE statement_imports ADD CONSTRAINT statement_imports_file_content_or_object_key
    CHECK (file_content IS NOT NULL OR object_key IS NOT NULL);
ALTER TABLE import_sessions ADD CONSTRAINT import_sessions_file_content_or_object_key
    CHECK (file_content IS NOT NULL OR object_key IS NOT NULL);
