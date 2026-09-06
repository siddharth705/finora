-- Makes "the classifier has never run on this row" distinguishable from "the classifier ran and
-- found nothing identifiable".
--
-- V142 gave counterparty_type NOT NULL DEFAULT 'UNKNOWN'. That is the honest default for a new
-- column, but it collapses two states that are not the same fact: UNKNOWN is a REAL answer in this
-- vocabulary -- the classifier returns it for roughly a fifth of real rows -- so every historical
-- row currently claims to have been examined and found unidentifiable, when in truth nothing has
-- looked at it yet. Without this column the backfill has no correct predicate to select on, and the
-- review UX this layer exists to serve cannot tell an exhausted row from an untouched one.
--
-- Nullable with no default, deliberately: NULL is the third state, and it has to be the state every
-- existing row lands in.

-- SMALLINT and not BOOLEAN. The rules changed three times in the week they were written
-- (#790, #794, #815), each revision recognising counterparties the last one could not. The
-- operation that will actually be wanted is "re-type every row typed by an older vocabulary", which
-- a version answers directly, a boolean cannot answer at all, and a timestamp answers only if
-- somebody can still map deploy times to classifier changes months from now.
--
-- Named ..._classifier_version, not ..._version: transactions already has a `version` column for
-- optimistic locking, and two columns a reader could confuse for each other is a trap worth five
-- extra characters.
ALTER TABLE transactions
    ADD COLUMN counterparty_classifier_version SMALLINT;

-- Serves the backfill's discovery predicate, which is
--   counterparty_classifier_version IS NULL OR counterparty_classifier_version < :current
--
-- A plain btree rather than a partial index on IS NULL: the partial one would be smaller and would
-- serve the steady state perfectly, but it goes blind the moment CounterpartyClassifier.VERSION is
-- bumped -- precisely when the backfill has the most work to do and least wants a sequential scan
-- of the whole table on every pass. Postgres indexes NULLs in a btree, so one index answers both
-- halves of the OR.
--
-- In the drained steady state every row holds the current version and the predicate matches nothing,
-- so the scheduled sweep costs an empty index probe rather than a table scan. That is the state this
-- table spends nearly all of its life in.
CREATE INDEX idx_transactions_counterparty_classifier_version
    ON transactions (counterparty_classifier_version);
