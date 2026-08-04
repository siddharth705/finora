-- Records how long an import took, so "do recurring layouts import faster than first-time ones?"
-- can be answered from data instead of assumed.
--
-- The value was ALREADY being computed at confirm time (ImportService.confirm times itself and
-- returns importDurationMs on ConfirmResponse) -- it was just handed to the client and dropped.
-- This persists the number that was already there; nothing about parsing or confirming changes.
--
-- Nullable with no backfill on purpose. Every row that already exists was imported before this
-- column did, and inventing a duration for them would put fabricated numbers into the exact report
-- that is supposed to decide whether layout reuse is worth building. NULL means "not measured",
-- which is true, and the layout evidence report excludes those rows rather than treating them as
-- zero. Analysis therefore only becomes meaningful once enough imports have run since this shipped.
ALTER TABLE statement_imports ADD COLUMN import_duration_ms BIGINT;

COMMENT ON COLUMN statement_imports.import_duration_ms IS
    'Wall-clock ms for the confirm step that created this row. NULL for imports predating V53.';
