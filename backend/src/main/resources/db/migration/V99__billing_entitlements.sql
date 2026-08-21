-- D-28 PR4-A. Core entitlement architecture per docs/proposals/billing-subscription-entitlements-
-- proposal.md §3.1/3.1a/3.1b/3.2 -- schema only, no payment gateway (none selected yet, §10).
--
-- plans: the Product-approved Free/Plus/Premium taxonomy (Billing Plan Taxonomy Decision,
-- 2026-08-12), seeded from frontend/src/pages/landing/plans.ts's own tier set so the two can't
-- drift into describing different products -- price stays NULL for Plus/Premium (matching
-- plans.ts's own "price may only be set on a plan whose availability is 'available'" rule; only
-- Free is purchasable today).
CREATE TABLE plans (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(20) NOT NULL UNIQUE,
    name VARCHAR(50) NOT NULL,
    price NUMERIC(10, 2),
    billing_cycle VARCHAR(20),
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0
);

-- One subscription row is a user's current standing; subscription_events (below) is why it
-- changed. Soft-deletable like every other core domain entity in this codebase (Goal, Budget,
-- StatementImport) -- see those entities' own @SQLDelete comments for why the version column is
-- mandatory alongside it.
CREATE TABLE subscriptions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    plan_id UUID NOT NULL REFERENCES plans(id),
    status VARCHAR(20) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE,
    renewal_date DATE,
    trial_start DATE,
    trial_end DATE,
    payment_provider VARCHAR(30),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_subscriptions_user_id ON subscriptions(user_id);
-- At most one ACTIVE-or-TRIAL subscription per user at a time -- enforced by the database, not
-- just by service-layer discipline, same reasoning as every other invariant this codebase enforces
-- with a constraint rather than trusting every future call site to remember a rule.
CREATE UNIQUE INDEX idx_subscriptions_one_active_per_user ON subscriptions(user_id)
    WHERE status IN ('ACTIVE', 'TRIAL') AND deleted_at IS NULL;

-- Append-only lifecycle log (proposal §3.1a) -- answers "why did premium subscribers drop
-- yesterday", which the live subscriptions row alone can't. Same shape as audit_logs: no
-- soft-delete, no version, write-once.
CREATE TABLE subscription_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id UUID NOT NULL REFERENCES subscriptions(id),
    event_type VARCHAR(30) NOT NULL,
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_subscription_events_subscription_id ON subscription_events(subscription_id);

-- Append-only upgrade/downgrade history (proposal §3.1b). effective_at deliberately separate from
-- created_at: a change can be recorded now but take effect at the next renewal_date -- timing
-- itself is a product decision this schema supports without presupposing (D-28/§10).
CREATE TABLE plan_changes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id UUID NOT NULL REFERENCES subscriptions(id),
    from_plan_id UUID REFERENCES plans(id),
    to_plan_id UUID NOT NULL REFERENCES plans(id),
    effective_at TIMESTAMPTZ NOT NULL,
    reason VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_plan_changes_subscription_id ON plan_changes(subscription_id);

-- Fail-CLOSED lookup table (proposal §3.2, Correction #3) -- deliberately the opposite default
-- from feature_flags (V32), which fails open. An entitlement check must never be tempted to reuse
-- FeatureFlagRepository.isEnabled's fail-open convention: a missing/mistyped feature_key here
-- means "no access", not "everyone gets it free."
CREATE TABLE feature_entitlements (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id UUID NOT NULL REFERENCES plans(id),
    feature_key VARCHAR(50) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT true,
    UNIQUE (plan_id, feature_key)
);
CREATE INDEX idx_feature_entitlements_plan_id ON feature_entitlements(plan_id);

-- Seed: the three Product-approved plans, matching plans.ts's own id/name/price exactly.
INSERT INTO plans (code, name, price, billing_cycle, active) VALUES
    ('FREE', 'Free', 0, NULL, true),
    ('PLUS', 'Plus', NULL, NULL, true),
    ('PREMIUM', 'Premium', NULL, NULL, true);

-- Seed: the Product-approved entitlement mapping (proposal §3.2's table), not invented here.
INSERT INTO feature_entitlements (plan_id, feature_key, enabled)
    SELECT id, 'BASIC_DASHBOARD', true FROM plans WHERE code IN ('FREE', 'PLUS', 'PREMIUM');
INSERT INTO feature_entitlements (plan_id, feature_key, enabled)
    SELECT id, 'ADVANCED_REPORTS', true FROM plans WHERE code IN ('PLUS', 'PREMIUM');
INSERT INTO feature_entitlements (plan_id, feature_key, enabled)
    SELECT id, 'EXTENDED_HISTORY', true FROM plans WHERE code IN ('PLUS', 'PREMIUM');
INSERT INTO feature_entitlements (plan_id, feature_key, enabled)
    SELECT id, 'INVESTMENT_INSIGHTS', true FROM plans WHERE code = 'PREMIUM';
INSERT INTO feature_entitlements (plan_id, feature_key, enabled)
    SELECT id, 'FINO_AI', true FROM plans WHERE code = 'PREMIUM';
INSERT INTO feature_entitlements (plan_id, feature_key, enabled)
    SELECT id, 'PRIORITY_SUPPORT', true FROM plans WHERE code = 'PREMIUM';

-- Backfill: every user who already exists gets an ACTIVE Free subscription -- entitlement lookups
-- are fail-closed (no row = no access), so without this, every existing user would silently lose
-- BASIC_DASHBOARD the moment any code starts checking hasEntitlement() against it.
INSERT INTO subscriptions (user_id, plan_id, status, start_date)
    SELECT u.id, (SELECT id FROM plans WHERE code = 'FREE'), 'ACTIVE', CURRENT_DATE
    FROM users u
    WHERE NOT EXISTS (SELECT 1 FROM subscriptions s WHERE s.user_id = u.id);

-- Admin capability to view/manage subscriptions -- its own permission rather than folding into
-- PLATFORM_STATS_VIEW/PLATFORM_ANALYTICS_VIEW, same reasoning V30's own migration comment gives
-- for why analytics got a separate permission from stats: a distinct capability an admin could
-- reasonably be granted independently of the others.
INSERT INTO permissions (name, description) VALUES
    ('SUBSCRIPTION_MANAGEMENT_VIEW', 'View user subscriptions and plan history.'),
    ('SUBSCRIPTION_MANAGEMENT_MANAGE', 'Change a user''s plan (e.g. granting Plus/Premium manually, no payment gateway yet).');

INSERT INTO role_permissions (role_id, permission_id)
    SELECT r.id, p.id FROM roles r, permissions p
    WHERE r.name IN ('ADMIN', 'SUPER_ADMIN') AND p.name IN ('SUBSCRIPTION_MANAGEMENT_VIEW', 'SUBSCRIPTION_MANAGEMENT_MANAGE');
