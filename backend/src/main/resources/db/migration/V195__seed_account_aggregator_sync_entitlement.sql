-- Account Aggregator bank/card sync moves behind Premium, same reasoning as V163's GMAIL_SYNC:
-- this is the one integration with a genuine ongoing per-user cost (Setu bills per data pull for
-- as long as a link stays active), unlike the rest of the app's free CRUD/in-process computation.
-- See docs/superpowers/specs/2026-09-12-account-aggregator-sync-design.md, "Cost control".
INSERT INTO feature_entitlements (plan_id, feature_key, enabled)
    SELECT id, 'ACCOUNT_AGGREGATOR_SYNC', true FROM plans WHERE code = 'PREMIUM';
