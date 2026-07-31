-- ============================================================================
-- Finora — Initial schema (Phase 1: Personal Finance Core)
-- ============================================================================

CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email           VARCHAR(255) NOT NULL UNIQUE,
    password_hash   VARCHAR(255) NOT NULL,
    full_name       VARCHAR(255) NOT NULL,
    role            VARCHAR(30)  NOT NULL DEFAULT 'USER',   -- USER | ADMIN (RBAC placeholder for Phase 5)
    low_balance_threshold NUMERIC(14,2) NOT NULL DEFAULT 2000,
    theme           VARCHAR(20)  NOT NULL DEFAULT 'ledger',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Every table below scopes rows to a user via user_id, which is how
-- multi-tenancy is enforced at the application layer in Phase 1.
-- True schema-level multi-tenant isolation (separate schemas/row-level
-- security policies) is a Phase 5 concern — see README "Known Gaps".

CREATE TABLE accounts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name            VARCHAR(120) NOT NULL,
    account_type    VARCHAR(30)  NOT NULL,  -- SAVINGS | CREDIT_CARD | WALLET | INVESTMENT
    balance         NUMERIC(14,2) NOT NULL DEFAULT 0,
    credit_limit    NUMERIC(14,2),          -- only meaningful for CREDIT_CARD
    due_date        DATE,                   -- only meaningful for CREDIT_CARD
    investment_kind VARCHAR(40),            -- only meaningful for INVESTMENT (Mutual Fund, Stocks, FD, ...)
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_accounts_user ON accounts(user_id);

CREATE TABLE categories (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name            VARCHAR(80) NOT NULL,
    is_system       BOOLEAN NOT NULL DEFAULT false,
    UNIQUE(user_id, name)
);

CREATE TABLE merchant_category_map (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    normalized_desc VARCHAR(255) NOT NULL,
    category_id     UUID NOT NULL REFERENCES categories(id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(user_id, normalized_desc)
);

CREATE TABLE transactions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    account_id          UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    category_id         UUID REFERENCES categories(id) ON DELETE SET NULL,
    txn_date            DATE NOT NULL,
    description         VARCHAR(500),
    merchant            VARCHAR(255),
    payment_method      VARCHAR(60),
    amount              NUMERIC(14,2) NOT NULL,
    txn_type            VARCHAR(10) NOT NULL,   -- INCOME | EXPENSE
    tags                VARCHAR(255)[],
    notes               TEXT,
    is_duplicate_of     UUID REFERENCES transactions(id),
    is_transfer         BOOLEAN NOT NULL DEFAULT false,
    transfer_pair_id    UUID REFERENCES transactions(id),
    is_recurring        BOOLEAN NOT NULL DEFAULT false,
    reconciliation_status VARCHAR(20) NOT NULL DEFAULT 'OK',  -- OK | DUPLICATE | TRANSFER
    source              VARCHAR(20) NOT NULL DEFAULT 'MANUAL', -- MANUAL | CSV_IMPORT
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_txn_user_date ON transactions(user_id, txn_date DESC);
CREATE INDEX idx_txn_account ON transactions(account_id);
CREATE INDEX idx_txn_category ON transactions(category_id);

CREATE TABLE budgets (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    category_id     UUID NOT NULL REFERENCES categories(id) ON DELETE CASCADE,
    monthly_limit   NUMERIC(14,2) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(user_id, category_id)
);

CREATE TABLE goals (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name            VARCHAR(120) NOT NULL,
    target_amount   NUMERIC(14,2) NOT NULL,
    current_amount  NUMERIC(14,2) NOT NULL DEFAULT 0,
    target_date     DATE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE goal_contributions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    goal_id         UUID NOT NULL REFERENCES goals(id) ON DELETE CASCADE,
    amount          NUMERIC(14,2) NOT NULL,
    contributed_at  DATE NOT NULL DEFAULT CURRENT_DATE
);

CREATE TABLE net_worth_snapshots (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    snapshot_date   DATE NOT NULL,
    total_assets    NUMERIC(14,2) NOT NULL,
    total_liabilities NUMERIC(14,2) NOT NULL,
    net_worth       NUMERIC(14,2) NOT NULL,
    UNIQUE(user_id, snapshot_date)
);

-- Audit log (Phase 1 minimal version — append-only, no PII beyond user_id/action)
CREATE TABLE audit_logs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID REFERENCES users(id) ON DELETE SET NULL,
    action          VARCHAR(100) NOT NULL,
    entity_type     VARCHAR(60),
    entity_id       UUID,
    metadata        JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_user ON audit_logs(user_id, created_at DESC);

-- Seed default categories per new user is handled in application code at registration time
-- (see CategoryService.seedDefaultCategories), not here, since it needs a user_id to attach to.
