-- Statement Import history, organized by account rather than a flat list of uploaded files
-- (see StatementImportService / the Statement History frontend page). Every confirmed CSV
-- import (first-time or re-import) creates exactly one row here, and every Transaction it
-- produced links back to it via statement_import_id — that link is what makes "delete this
-- statement" precise (only its own transactions go) instead of an all-or-nothing wipe.
CREATE TABLE statement_imports (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                  UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    account_id               UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    file_name                VARCHAR(255) NOT NULL,
    -- Raw bytes of the originally uploaded CSV, kept so "Re-import Statement" can replay the
    -- exact file and "Download Original File" has something to serve — a deliberate storage
    -- trade-off (this table will grow) accepted in exchange for both of those actually working.
    file_content             BYTEA NOT NULL,
    statement_period_start   DATE,
    statement_period_end     DATE,
    opening_balance          NUMERIC(14, 2),
    closing_balance          NUMERIC(14, 2),
    transactions_imported    INT NOT NULL DEFAULT 0,
    transactions_skipped     INT NOT NULL DEFAULT 0,
    status                   VARCHAR(20) NOT NULL DEFAULT 'COMPLETED',
    imported_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at               TIMESTAMPTZ,
    version                  BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_statement_imports_user_account_active ON statement_imports(user_id, account_id) WHERE deleted_at IS NULL;

-- Nullable because manual transactions, and every transaction imported before this migration,
-- have no statement to point back to.
ALTER TABLE transactions ADD COLUMN statement_import_id UUID REFERENCES statement_imports(id) ON DELETE SET NULL;
CREATE INDEX idx_transactions_statement_import ON transactions(statement_import_id) WHERE deleted_at IS NULL;
