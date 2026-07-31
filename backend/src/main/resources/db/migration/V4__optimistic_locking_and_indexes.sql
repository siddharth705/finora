-- Optimistic locking: prevents lost updates when two sessions edit the same row concurrently
-- (e.g. two browser tabs both editing the same budget). Hibernate increments this on every
-- UPDATE and rejects a write if the version it's holding is stale.
ALTER TABLE accounts     ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE transactions ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE budgets      ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE goals        ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- Composite + partial indexes matching actual query patterns. Partial (WHERE deleted_at IS NULL)
-- indexes are smaller and faster than full-table indexes now that soft delete means most queries
-- filter on that predicate anyway.
CREATE INDEX idx_transactions_user_account_active ON transactions(user_id, account_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_transactions_user_category_active ON transactions(user_id, category_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_accounts_user_active ON accounts(user_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_budgets_user_active ON budgets(user_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_goals_user_active ON goals(user_id) WHERE deleted_at IS NULL;

-- Account lockout after repeated failed logins.
ALTER TABLE users ADD COLUMN failed_login_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN locked_until TIMESTAMPTZ;

-- NOTE: an earlier draft of this migration incorrectly added created_at/updated_at to budgets
-- and goals here, on the false premise that V1 never created them. It did — see
-- V1__init_schema.sql's CREATE TABLE budgets / CREATE TABLE goals. The real gap was only that
-- the Java entity classes (Budget.java, Goal.java) didn't map those already-existing columns —
-- fixed by having them extend BaseEntity, no schema change required. Leaving this note in place
-- rather than silently deleting the mistake, since it's exactly the kind of thing worth being
-- able to see happened.
