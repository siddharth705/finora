-- Whether an operator determined, at approve time, that the trust predicate's flag was wrong --
-- the extraction was actually fine. Explicit and deliberate, never inferred from whether
-- root_cause/fix_reference were filled in or a parser re-run happened first: see Plan 4's own
-- Decisions table for why an inferred signal would be indistinguishable from an operator who
-- simply already knew, from memory, that nothing was wrong.
--
-- Nullable, with no default: null means "not marked either way" -- most holds are never asked
-- about, and a hold rejected instead of approved never gets this set at all (see the reject-has-
-- no-such-parameter decision in the same table). Only ever set once, at approve.
ALTER TABLE held_statements ADD COLUMN false_positive BOOLEAN;

COMMENT ON COLUMN held_statements.false_positive IS
    'Set only by an explicit operator mark at approve time. Null means never marked, not false.';
