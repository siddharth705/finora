-- Design: docs/superpowers/specs/2026-08-30-absolute-balance-reversal-design.md
-- Makes an ABSOLUTE-mode statement's contribution to Account.balance actually reversible.
-- StatementImportService.supersede/delete previously could not safely reverse a statement that
-- had SET Account.balance directly (as opposed to moving it by a delta) -- nothing about that
-- SET is reconstructible after the fact (see BalanceApplicationMode's own doc comment on why
-- recomputing from totalCredits/totalDebits or from opening/closing arithmetic is unsafe).

-- Account.balance immediately before this statement's own confirm overwrote it, captured once at
-- confirm time by ImportService.persistSection's ABSOLUTE branch. NULL for every row that isn't
-- ABSOLUTE mode, and for every ABSOLUTE row confirmed before this migration -- never backfilled,
-- same "never guess, never reconstruct" stance V119 already took for balance_application_mode
-- itself. StatementImportService.reverseAbsoluteContribution is the sole reader.
ALTER TABLE statement_imports ADD COLUMN balance_before_absolute_set NUMERIC(14, 2) NULL;

-- Which statement most recently SET (not added to) this account's balance -- NULL means either
-- nothing ever has, or a manual balance edit (AccountService.update) invalidated the previous
-- claim. Lets a later reversal tell "this statement's SET is still the account's live anchor"
-- apart from "something else already overwrote it" (a later-period ABSOLUTE statement, or a
-- manual edit) without reconstructing history -- see the design spec's "live anchor" section.
-- No FOREIGN KEY, same reasoning as statement_imports.superseded_by (V119): the referenced row
-- can be soft-deleted later, and a hard FK's ON DELETE behavior doesn't fit a soft-delete model.
ALTER TABLE accounts ADD COLUMN last_absolute_set_statement_id UUID NULL;
