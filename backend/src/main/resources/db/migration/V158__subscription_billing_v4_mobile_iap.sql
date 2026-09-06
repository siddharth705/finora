-- Subscription billing V4 (docs/superpowers/specs/2026-09-06-subscription-billing-v4-mobile-iap-design.md).
-- Mobile in-app purchase via RevenueCat.

ALTER TABLE subscriptions ADD COLUMN store_platform VARCHAR(10);
ALTER TABLE subscriptions ADD COLUMN revenuecat_original_transaction_id VARCHAR(100);

CREATE TABLE iap_products (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_product_id  VARCHAR(100) NOT NULL,
    plan_id              UUID NOT NULL REFERENCES plans(id),
    billing_cycle        VARCHAR(10) NOT NULL,
    platform             VARCHAR(10) NOT NULL,
    active               BOOLEAN NOT NULL DEFAULT true,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (provider_product_id, platform)
);
-- Rows populated by a one-time setup step (spec §10) once App Store Connect / Play Console
-- products exist -- same posture as billing_prices.razorpay_plan_id staying NULL until Razorpay's
-- own one-time setup (V154's own comment).
