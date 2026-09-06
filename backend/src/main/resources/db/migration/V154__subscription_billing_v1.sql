-- Subscription billing V1 (docs/superpowers/specs/2026-09-05-subscription-billing-v1-design.md).
-- Replaces admin-only manual plan changes with Razorpay Subscriptions-backed self-service billing.

-- price/billing_cycle move to billing_prices below -- price is cycle-dependent for paid tiers,
-- so a single column on plans can no longer represent it.
ALTER TABLE plans DROP COLUMN price;
ALTER TABLE plans DROP COLUMN billing_cycle;

CREATE TABLE billing_prices (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id           UUID NOT NULL REFERENCES plans(id),
    billing_cycle     VARCHAR(10) NOT NULL,
    price             NUMERIC(10, 2) NOT NULL,
    currency          VARCHAR(3) NOT NULL DEFAULT 'INR',
    razorpay_plan_id  VARCHAR(50),
    active            BOOLEAN NOT NULL DEFAULT true,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_billing_prices_plan_cycle_active ON billing_prices(plan_id, billing_cycle)
    WHERE active;

INSERT INTO billing_prices (plan_id, billing_cycle, price)
    SELECT id, 'MONTHLY', 399.00 FROM plans WHERE code = 'PLUS';
INSERT INTO billing_prices (plan_id, billing_cycle, price)
    SELECT id, 'YEARLY', 3500.00 FROM plans WHERE code = 'PLUS';
INSERT INTO billing_prices (plan_id, billing_cycle, price)
    SELECT id, 'MONTHLY', 799.00 FROM plans WHERE code = 'PREMIUM';
INSERT INTO billing_prices (plan_id, billing_cycle, price)
    SELECT id, 'YEARLY', 8000.00 FROM plans WHERE code = 'PREMIUM';
-- razorpay_plan_id stays NULL until the one-time Razorpay-account setup step (spec §10) populates
-- it -- checkout refuses with a clear error until then (Task 6), not a silent failure.

ALTER TABLE subscriptions ADD COLUMN billing_cycle VARCHAR(10);
ALTER TABLE subscriptions ADD COLUMN razorpay_subscription_id VARCHAR(50);
ALTER TABLE subscriptions ADD COLUMN auto_renew BOOLEAN NOT NULL DEFAULT true;
CREATE INDEX idx_subscriptions_razorpay_subscription_id ON subscriptions(razorpay_subscription_id)
    WHERE razorpay_subscription_id IS NOT NULL;

CREATE TABLE subscription_orders (
    id                         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                    UUID NOT NULL REFERENCES users(id),
    plan_id                    UUID NOT NULL REFERENCES plans(id),
    billing_cycle              VARCHAR(10) NOT NULL,
    razorpay_subscription_id   VARCHAR(50),
    status                     VARCHAR(20) NOT NULL,
    amount                     NUMERIC(10, 2) NOT NULL,
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at               TIMESTAMPTZ
);
CREATE INDEX idx_subscription_orders_user_id ON subscription_orders(user_id);
CREATE INDEX idx_subscription_orders_razorpay_subscription_id
    ON subscription_orders(razorpay_subscription_id) WHERE razorpay_subscription_id IS NOT NULL;

-- Idempotency ledger (spec §4.7). PK is Razorpay's own event id, not a generated UUID -- the whole
-- point is a natural key the ON CONFLICT clause can target.
CREATE TABLE webhook_events (
    event_id      VARCHAR(50) PRIMARY KEY,
    provider      VARCHAR(20) NOT NULL,
    event_type    VARCHAR(50) NOT NULL,
    payload       JSONB,
    status        VARCHAR(20),
    processed_at  TIMESTAMPTZ
);
