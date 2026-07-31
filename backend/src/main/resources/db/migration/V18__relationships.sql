-- Relationship Engine (docs/rule-engine-relationship-engine-eds.md §3.3): lets a user tag other
-- people/accounts as family, friend, or one of their own other accounts, and use that tag to
-- strengthen transfer detection beyond the existing amount+date heuristic in ReconciliationService.
CREATE TABLE relationships (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id            UUID NOT NULL REFERENCES users(id),
    label              VARCHAR(100) NOT NULL,
    relationship_type  VARCHAR(20) NOT NULL,          -- FAMILY | FRIEND | OWN_ACCOUNT | OTHER
    linked_account_id  UUID REFERENCES accounts(id),  -- set only for OWN_ACCOUNT
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_relationships_user ON relationships(user_id);

-- Matched the same way MerchantAlias matches a merchant: an exact hit on a normalized identifier,
-- not fuzzy text matching. One relationship can carry several identifiers (a person might be
-- reachable via more than one UPI id, or an account known by more than one masked number).
CREATE TABLE relationship_identifiers (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    relationship_id   UUID NOT NULL REFERENCES relationships(id) ON DELETE CASCADE,
    identifier_type   VARCHAR(20) NOT NULL,   -- UPI_ID | ACCOUNT_LAST4 | NAME_PATTERN
    identifier_value  VARCHAR(200) NOT NULL
);

CREATE INDEX idx_relationship_identifiers_relationship ON relationship_identifiers(relationship_id);
CREATE INDEX idx_relationship_identifiers_value ON relationship_identifiers(identifier_value);
