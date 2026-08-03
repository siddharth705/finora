-- Financial Product Discovery, Phase 2: give every discovered product a stable identity.
--
-- Classification is about to recognise the same fixed deposit every month. Without a key, each
-- re-import of a combined statement creates ANOTHER fixed deposit and double-counts it in net
-- worth. Adding identity after duplicates exist is a data migration plus a merge UI, so it lands
-- with the classification rather than after it.
--
-- product_type is the FinancialProductType, which is strictly finer-grained than account_type:
-- SAVINGS/CURRENT/OVERDRAFT all store as account_type SAVINGS, and FD/RD/PPF/EPF/NPS/mutual
-- fund/demat all store as INVESTMENT. Keeping the classification means the day a current account
-- needs different treatment, the information is already there rather than needing re-derivation
-- from a statement nobody kept.
ALTER TABLE accounts ADD COLUMN IF NOT EXISTS product_type VARCHAR(32);

-- A one-way hash of institution + the product's OWN full number.
--
-- A hash rather than the number itself, deliberately. Exact identity matching needs a value that
-- compares equal across imports; it does not need a readable account number, and a full account
-- number in a column that exists purely for equality checks is customer data stored for no reason
-- anyone would remember. accounts.account_number_masked already holds the last-4 for display --
-- that stays the weak fallback signal, this is the strong one.
ALTER TABLE accounts ADD COLUMN IF NOT EXISTS product_identity_hash VARCHAR(64);

-- Scoped by user: identity is only ever resolved within one person's own products, so a global
-- unique constraint would be both wrong (two customers can hold the same FD number at different
-- banks) and a cross-tenant leak waiting to happen. Not UNIQUE even per user -- a genuine duplicate
-- is a decision for the review screen, not something the database should reject mid-import.
CREATE INDEX IF NOT EXISTS idx_accounts_user_product_identity
    ON accounts(user_id, product_identity_hash);

-- Backfill: every existing account keeps behaving exactly as before. product_type is derived from
-- what we already know, so no account silently becomes "unclassified" -- an INVESTMENT row's
-- investment_kind already carries the finer type where it was set.
UPDATE accounts SET product_type = CASE
    WHEN account_type = 'CREDIT_CARD' THEN 'CREDIT_CARD'
    WHEN account_type = 'WALLET' THEN 'WALLET'
    WHEN account_type = 'INVESTMENT' AND investment_kind = 'FD' THEN 'FIXED_DEPOSIT'
    WHEN account_type = 'INVESTMENT' AND investment_kind = 'RD' THEN 'RECURRING_DEPOSIT'
    WHEN account_type = 'INVESTMENT' AND investment_kind = 'PPF' THEN 'PPF'
    WHEN account_type = 'INVESTMENT' AND investment_kind = 'EPF' THEN 'EPF'
    WHEN account_type = 'INVESTMENT' AND investment_kind = 'NPS' THEN 'NPS'
    WHEN account_type = 'INVESTMENT' AND investment_kind = 'Demat' THEN 'DEMAT'
    WHEN account_type = 'INVESTMENT' AND investment_kind = 'Mutual Fund' THEN 'MUTUAL_FUND'
    WHEN account_type = 'INVESTMENT' THEN 'MUTUAL_FUND'
    ELSE 'SAVINGS'
END
WHERE product_type IS NULL;
