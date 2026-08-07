-- WI5 follow-up: record that a HUMAN ruled on a flagged row, so reconciliation stops overruling them.
--
-- The defect this closes was found by the milestone validation gate, driving the real UI against a
-- real database. A user reviewed two identical METRO FARE charges, chose "Import anyway" -- the
-- correct answer, they genuinely commute twice a day -- and the rows landed in the ledger. Then
-- ReconciliationService's duplicate pass ran, saw two rows sharing a duplicate key, and marked the
-- later one is_duplicate_of. Every spend calculation filters is_duplicate_of IS NULL
-- (BudgetService, AnalyticsService, DashboardService, InsightsService, RecurringService,
-- ReportService, and two TransactionRepository aggregates), so:
--
--     ledger total:            Rs 1,618.50
--     dashboard reported:      Rs 1,528.50
--
-- The Rs 90 difference was exactly the two fares the user had explicitly asked for. The decision
-- was honoured in the ledger and reversed in the numbers.
--
-- WI5 removed silent auto-skipping from the import screen; this removes it from everything
-- downstream of the import screen. Without it, WI5 offers a decision the system then quietly
-- overrides, which is worse than not offering it -- the user was told it was handled.
--
-- A timestamp, not a boolean, for three reasons:
--   * NULL is unambiguously "no human has ruled", with no default-value question to answer.
--   * WHEN a person ruled is auditable; "true" is not.
--   * It reads correctly at the guard site: `if (t.getNotDuplicateConfirmedAt() != null) continue;`
--
-- Deliberately NOT backfilled. Every existing row where is_duplicate_of is set was marked by the
-- engine with no human in the loop, which is precisely the state this column exists to distinguish
-- from. Inventing a confirmation timestamp for those would erase the distinction on its first day.
ALTER TABLE transactions
    ADD COLUMN not_duplicate_confirmed_at TIMESTAMPTZ;

COMMENT ON COLUMN transactions.not_duplicate_confirmed_at IS
    'When the user explicitly confirmed this transaction is NOT a duplicate, despite the import '
    'engine flagging it. Set from the duplicate review screen''s "Import anyway". Reconciliation''s '
    'duplicate pass never re-marks a row that carries this, so the decision survives every later '
    'reconciliation run -- not just the one that follows the import.';

-- Partial: these rows are rare by nature (only ever set by an explicit human decision on a flagged
-- row), so indexing only them keeps the index proportional to the decisions actually made rather
-- than to the transactions table.
CREATE INDEX idx_transactions_not_duplicate_confirmed
    ON transactions (user_id)
    WHERE not_duplicate_confirmed_at IS NOT NULL;
