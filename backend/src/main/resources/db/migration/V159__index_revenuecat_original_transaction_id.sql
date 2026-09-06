-- Subscription billing V4 follow-up (bug-hunt review). V158 added
-- subscriptions.revenuecat_original_transaction_id but no index -- RevenueCatWebhookDispatcher
-- looks the subscription up by this column for every event except INITIAL_PURCHASE (RENEWAL,
-- CANCELLATION, UNCANCELLATION, EXPIRATION, BILLING_ISSUE, PRODUCT_CHANGE), so every one of those
-- webhook deliveries would otherwise full-scan a table that grows roughly 1:1 with the user base
-- (SubscriptionRepository's own doc comment). Mirrors V99's
-- idx_subscriptions_razorpay_subscription_id exactly -- same partial-index shape, same reasoning:
-- the column is null for every non-owning row, so a full index would waste space indexing nulls.
CREATE INDEX idx_subscriptions_revenuecat_original_transaction_id ON subscriptions(revenuecat_original_transaction_id)
    WHERE revenuecat_original_transaction_id IS NOT NULL;
