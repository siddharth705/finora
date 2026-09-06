-- Seeds FeatureEntitlement.UNLIMITED_ACCOUNTS, the enforcement behind plans.ts's existing
-- "Unlimited accounts" Plus/Premium promise -- that bullet has described the product since the
-- V99 taxonomy was seeded, but nothing ever checked it: AccountService.create() had no cap for
-- any plan. Same fail-closed shape as every other key in feature_entitlements (V99's own comment):
-- absent for FREE means AccountService's Free-tier 2-account cap applies; present and enabled for
-- PLUS/PREMIUM means it does not.
INSERT INTO feature_entitlements (plan_id, feature_key, enabled)
    SELECT id, 'UNLIMITED_ACCOUNTS', true FROM plans WHERE code IN ('PLUS', 'PREMIUM');
