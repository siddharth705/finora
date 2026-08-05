-- Why a transaction was classified the way reconciliation classified it.
--
-- Reconciliation already recorded its VERDICT (reconciliation_status) and its COUNTERPART
-- (transfer_pair_id, refund_of_transaction_id, is_duplicate_of). It recorded nothing about the
-- reasoning: three passes weigh five or six signals each, and all of that was discarded the moment
-- it produced an answer. "Why was my salary excluded from income?" could only be answered by
-- re-deriving the decision by hand from the pair -- which stops working as soon as either side is
-- edited, and edits are exactly what prompt the question.
--
-- Nullable, and deliberately not backfilled. A NULL here means "classified before this column
-- existed", which is honest. The alternative -- reconstructing explanations for historical rows --
-- would mean re-running the passes against today's data and today's thresholds and presenting the
-- result as the reason a past decision was made, which it would not be. An absent explanation is
-- better than a plausible invented one, on a table that records money.
--
-- JSONB rather than a set of typed columns: the signals differ per verdict (a transfer has a day
-- window and a relationship-identifier flag; a refund has a keyword flag and a partial-refund
-- flag), and modelling their union as columns would mean a wide table where most values are NULL
-- for most rows, plus a migration every time a pass learns a new signal. This mirrors
-- audit_logs.metadata, which is JSONB for the same reason (V1__init_schema.sql).
--
-- No index. Nothing queries by explanation content today, and an index on a JSONB column that
-- nothing filters on is write cost with no read benefit. Add a GIN index alongside the first query
-- that actually needs one -- see docs/engineering/scaling-triggers.md on adding capacity to meet
-- a condition rather than a prediction.
ALTER TABLE transactions
    ADD COLUMN reconciliation_explanation JSONB;

COMMENT ON COLUMN transactions.reconciliation_explanation IS
    'Structured reason for reconciliation_status, written by ReconciliationService. NULL means the '
    'row was classified before this column existed, or has never been matched. Never backfilled: a '
    'reconstructed reason is not the reason a past decision was made.';
