-- Phase 4 of docs/proposals/statement-continuity-and-coverage-integrity-proposal.md: statement
-- supersession. A statement can now be explicitly replaced by a later re-upload of the exact same
-- period, without deleting the original row (its history stays queryable, same as a superseded
-- Transaction stays in the ledger instead of being removed -- see the reconciliation_status
-- comment below).

-- Which statement replaced this one, if any. NULL for every live, active statement. A superseded
-- statement's transactions stop counting toward Account.balance, coverage, and Insights (see
-- StatementCoverageAnalyzer's callers and RefundNetting.reportable) the same way a
-- TRANSFER-classified transaction already stops counting toward expense totals -- reused
-- precedent, not a new mechanism (proposal §0.3/§0.6).
--
-- References statement_imports(id) rather than being enforced with a FOREIGN KEY: this table's
-- own @SQLDelete soft-delete pattern (see StatementImport's class comment) means a superseding
-- row could later be soft-deleted while still referenced here, which a hard FK would either block
-- or (with ON DELETE SET NULL) silently unwind -- neither is correct for a soft-delete model. The
-- application is the sole writer of this column (StatementImportService.supersede), the same
-- trust boundary already relied on for every other cross-row reference on this entity.
ALTER TABLE statement_imports ADD COLUMN superseded_by UUID NULL;

-- The account-balance branch this statement's own confirm actually took, captured once at confirm
-- time so a future supersede decision never has to recompute history -- see ImportService
-- .persistSection, which sets this in the same place it already decides the branch, and
-- StatementImportService.supersede, the only reader.
--
-- Defaults every pre-existing row to UNKNOWN_LEGACY rather than attempting to infer ABSOLUTE vs.
-- ADDITIVE from what's on file today: totalCredits/totalDebits were never persisted on this
-- table, and Transaction.amount is editable after import (TransactionService), so any inference
-- run now could silently disagree with what the original confirm actually did. UNKNOWN_LEGACY
-- keeps that history explicit instead of guessed, and StatementImportService.supersede refuses to
-- reverse a balance for it, surfacing an administrative warning instead. New rows always get an
-- explicit ABSOLUTE/ADDITIVE/NONE from persistSection before this default is ever read.
ALTER TABLE statement_imports ADD COLUMN balance_application_mode VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN_LEGACY';

-- reconciliation_status's V1/V20 comments already document this column has no DB-level CHECK
-- constraint (application-enforced only) -- SUPERSEDED joins OK/DUPLICATE/TRANSFER/REFUND/
-- REVERSAL/INVESTMENT_TRANSFER as a value RefundNetting.reportable() now excludes, marking a
-- superseded statement's own transactions the same "excluded from reporting, still on file" way
-- an INVESTMENT_TRANSFER row already is. No schema change needed for that value itself.
