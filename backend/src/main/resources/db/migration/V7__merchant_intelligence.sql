-- Merchant Intelligence layer (part of the broader Financial Intelligence Engine: merchant
-- resolution, categorization, learning, confidence, recurring/duplicate detection, analytics).
-- Supersedes the row-per-description merchant_category_map approach with a proper merchant
-- identity: many raw description variants (aliases) resolve to one canonical Merchant.
-- merchant_category_map is left in place (not dropped) — historical learned mappings there
-- aren't migrated automatically; that would mean guessing at alias groupings for old data
-- blind, which is riskier than just starting the new model fresh.
CREATE TABLE merchants (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    canonical_name  VARCHAR(255) NOT NULL,
    logo_url        VARCHAR(500),
    website         VARCHAR(500),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_merchants_user ON merchants(user_id);

-- Many raw normalized descriptions can point at the same merchant — this is what "AMAZON
-- SELLER SERVICES", "Amazon Pay", and "Amazon Marketplace" all resolving to one Merchant means
-- in practice: each is its own alias row pointing at the same merchant_id.
CREATE TABLE merchant_aliases (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id         UUID NOT NULL REFERENCES merchants(id) ON DELETE CASCADE,
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    normalized_alias    VARCHAR(255) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(user_id, normalized_alias)
);
CREATE INDEX idx_merchant_aliases_merchant ON merchant_aliases(merchant_id);

-- A merchant's category is a DISTRIBUTION, not a single value — Amazon can legitimately be
-- Shopping 71% of the time, Electronics 18%, Books 11%. One row per (merchant, category) pair,
-- with confirmation_count as the raw evidence and confidence as that pair's share of the
-- merchant's total confirmations (recomputed by the service layer whenever any pair's count
-- changes, not stored as an arbitrarily-incremented number).
CREATE TABLE merchant_category_learning (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id         UUID NOT NULL REFERENCES merchants(id) ON DELETE CASCADE,
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    category_id         UUID NOT NULL REFERENCES categories(id) ON DELETE CASCADE,
    confirmation_count  INT NOT NULL DEFAULT 1,
    confidence          INT NOT NULL DEFAULT 100, -- this pair's % share of the merchant's total confirmations
    last_confirmed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(user_id, merchant_id, category_id)
);
CREATE INDEX idx_merchant_learning_merchant ON merchant_category_learning(merchant_id);

-- Append-only history of every learning event for a merchant — what lets a user "undo" a
-- previous categorization decision, and what a support investigation would use to answer
-- "why does this merchant keep getting categorized as X".
CREATE TABLE merchant_learning_audit (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id         UUID NOT NULL REFERENCES merchants(id) ON DELETE CASCADE,
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    action              VARCHAR(20) NOT NULL, -- LEARNED | CORRECTED | UNDONE | MERGED
    previous_category_id UUID REFERENCES categories(id),
    new_category_id     UUID REFERENCES categories(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_merchant_audit_merchant ON merchant_learning_audit(merchant_id, created_at DESC);

-- Transactions now optionally resolve to a real merchant identity, not just a free-text string.
ALTER TABLE transactions ADD COLUMN merchant_id UUID REFERENCES merchants(id) ON DELETE SET NULL;
CREATE INDEX idx_transactions_merchant ON transactions(merchant_id) WHERE deleted_at IS NULL;
