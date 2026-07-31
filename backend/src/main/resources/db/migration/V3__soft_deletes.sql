-- Soft delete for financial records. Rather than physically deleting rows that represent
-- real money movements or account state, we mark them deleted_at and filter them out of
-- normal queries at the Hibernate layer (@SQLRestriction). This keeps a recoverable,
-- auditable history — important for anything touching real transaction data, not just a
-- nice-to-have.
ALTER TABLE transactions ADD COLUMN deleted_at TIMESTAMPTZ;
ALTER TABLE accounts     ADD COLUMN deleted_at TIMESTAMPTZ;
ALTER TABLE budgets      ADD COLUMN deleted_at TIMESTAMPTZ;
ALTER TABLE goals        ADD COLUMN deleted_at TIMESTAMPTZ;

CREATE INDEX idx_transactions_deleted_at ON transactions(deleted_at) WHERE deleted_at IS NOT NULL;
CREATE INDEX idx_accounts_deleted_at ON accounts(deleted_at) WHERE deleted_at IS NOT NULL;
