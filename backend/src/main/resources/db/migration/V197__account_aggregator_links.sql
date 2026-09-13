-- One row per user's link to one FI account via Setu's Account Aggregator API. Connection
-- lifecycle only -- see docs/superpowers/specs/2026-09-12-account-aggregator-sync-design.md,
-- "Architecture" and "Consent lifecycle". No transaction data lives here (Plan 2 adds that).
CREATE TABLE account_aggregator_links (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id               UUID NOT NULL,
    account_id            UUID,
    consent_handle_id     VARCHAR(255),
    fi_type               VARCHAR(20) NOT NULL,
    status                VARCHAR(32) NOT NULL,
    consent_expires_at    TIMESTAMPTZ,
    last_synced_at        TIMESTAMPTZ,
    link_idempotency_key  VARCHAR(255) NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Link-creation idempotency: two concurrent requests for the same client-minted attempt must
-- resolve to the same row, not two Setu consent requests. Per-user, not global -- two different
-- users could legitimately mint the same key value by coincidence.
CREATE UNIQUE INDEX idx_aa_links_user_idempotency_key
    ON account_aggregator_links (user_id, link_idempotency_key);

CREATE INDEX idx_aa_links_consent_handle_id ON account_aggregator_links (consent_handle_id);
CREATE INDEX idx_aa_links_account_id_status ON account_aggregator_links (account_id, status);
CREATE INDEX idx_aa_links_status_created_at ON account_aggregator_links (status, created_at);
