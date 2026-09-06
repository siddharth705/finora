-- Gmail statement/receipt sync moves behind Premium. Product decision (Sid, 2026-09-06): it's the
-- one integration with genuine ongoing marginal cost per connected user -- a scheduled worker
-- polling the Gmail API every 15 minutes for as long as the connection stays live -- unlike the
-- rest of the app's features, which are ordinary CRUD/in-process computation with no meaningful
-- per-user cost. See EntitlementService.hasEntitlement's fail-CLOSED contract: a Free or Plus user
-- gets no row here, so they're refused by default, not silently granted.
--
-- No FeatureEntitlement key already fit this (BASIC_DASHBOARD/ADVANCED_REPORTS/EXTENDED_HISTORY/
-- INVESTMENT_INSIGHTS/FINO_AI/PRIORITY_SUPPORT are all either free-for-all or about something
-- else entirely), so this adds one rather than repurposing an unrelated key.
INSERT INTO feature_entitlements (plan_id, feature_key, enabled)
    SELECT id, 'GMAIL_SYNC', true FROM plans WHERE code = 'PREMIUM';
