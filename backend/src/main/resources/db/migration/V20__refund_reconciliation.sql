-- Refund reconciliation (team priority: "Reconciliation Engine improvements -- salary, refunds,
-- credit card payments, self-transfers, cross-import duplicates"). A refund is neither a
-- duplicate nor a transfer -- it's a same-account reversal of a specific prior purchase, and
-- until now nothing linked it back to that purchase.
--
-- ON DELETE SET NULL for the same reason as decision_rule_id (V17): this is a reconciliation
-- pointer, not itself the financial fact -- if the original expense transaction is ever hard-
-- deleted (soft-delete via Transaction's @SQLDelete never actually removes the row, but this is
-- defensive either way), the refund transaction and its amount/date/category must survive intact.
ALTER TABLE transactions ADD COLUMN refund_of_transaction_id UUID REFERENCES transactions(id) ON DELETE SET NULL;

-- reconciliation_status's V1 comment ("OK | DUPLICATE | TRANSFER") is documentation only -- no DB
-- CHECK constraint exists, so no DDL change is required to allow the new Java-side REFUND value.
-- Restating it here for anyone reading migrations top-to-bottom rather than the entity source.
COMMENT ON COLUMN transactions.reconciliation_status IS 'OK | DUPLICATE | TRANSFER | REFUND';
