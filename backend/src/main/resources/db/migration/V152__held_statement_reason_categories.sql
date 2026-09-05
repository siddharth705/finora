-- The machine-readable tag behind each of TrustPredicate's reason sentences, snapshotted at hold
-- time the same way parser_version/reliability_status already are. Not derived from
-- trigger_summary -- that column is free text (see its own comment), and parsing it back apart
-- would tie a metrics query to TrustPredicate's exact reason-sentence wording. Populated from
-- TrustPredicate's own internal knowledge of which check produced which reason instead.
--
-- VARCHAR(64)[], matching the array-column precedent already in this codebase (transactions.tags,
-- V1__init_schema.sql). Nullable: a hold created before this migration has none, which is a fact,
-- not a zero -- a metrics query must be able to tell "no categories recorded" apart from "recorded
-- as empty," the same distinction VerificationTelemetry.isEmpty() already draws elsewhere in this
-- codebase for the identical reason.
ALTER TABLE held_statements ADD COLUMN hold_reason_categories VARCHAR(64)[];

COMMENT ON COLUMN held_statements.hold_reason_categories IS
    'Which of TrustPredicate''s conditions fired, snapshotted at hold time. Null for holds created before this column existed.';
