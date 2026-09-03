-- Persists the counterparty layer: WHO was on the other side of a transaction, as a separate
-- question from WHAT the money was for.
--
-- The classifier and identity derivation shipped in #790/#794/#815 as pure utilities that nothing
-- called. Measured on the real 29-statement corpus they type 79.2% of rows, against roughly 47%
-- that get a category -- "who" is simply an easier question than "why", and the answer was being
-- computed and thrown away.
--
-- Deliberately NOT a category. Counterparty type never appears beside Groceries/Dining/Transport:
-- one answers who, the other answers why, and conflating them is what produced the 130 rows that
-- were simultaneously "Amazon, Shopping" and "counterparty unknown" before #794.

-- VARCHAR rather than a DB enum or a CHECK constraint, following decision_source (V17): an
-- unrecognised value read back from an older or newer deploy degrades to something the application
-- can reason about, instead of failing a boot. The application maps it through
-- com.finora.util.CounterpartyType.
--
-- NOT NULL DEFAULT 'UNKNOWN' rather than nullable: every existing row genuinely IS untyped until
-- the backfill runs, and 'UNKNOWN' is a real answer in this vocabulary rather than a placeholder --
-- the classifier returns it for the ~21% of rows that carry no identifiable counterparty at all.
ALTER TABLE transactions
    ADD COLUMN counterparty_type VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN counterparty_key  VARCHAR(120);

-- The value-weighted review this exists to enable groups a user's rows by counterparty and orders
-- by summed value, so (user_id, counterparty_key) is exactly the access path. Partial: a NULL key
-- means "no identity derivable", which is never a group anyone reviews, and excluding those keeps
-- the index off the ~11% of rows that carry no key at all.
CREATE INDEX idx_transactions_user_counterparty
    ON transactions (user_id, counterparty_key)
    WHERE counterparty_key IS NOT NULL;
