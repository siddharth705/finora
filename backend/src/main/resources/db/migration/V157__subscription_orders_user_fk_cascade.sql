-- V154's subscription_orders gave user_id a plain REFERENCES users(id) with no ON DELETE behavior,
-- which defaults to NO ACTION -- the same gap V106 already fixed once for payments/subscriptions/
-- referral_codes/wallet_ledger, caught again by the same
-- e2e/tests/workflow/isolation.spec.ts "records which user_id foreign keys still block account
-- deletion" diagnostic (project rule: every user_id FK must be CASCADE, the row belongs to the
-- user, or SET NULL, an audit entry that must survive).
--
-- A subscription_orders row is a checkout/order record belonging to the user, exactly like
-- payments -- not an audit trail that must outlive them. Unlike payments/subscriptions, it has no
-- existing AccountPurgeSweepService.purgeOne hard-delete call to make this redundant; CASCADE is
-- this table's only deletion path today.
ALTER TABLE subscription_orders DROP CONSTRAINT subscription_orders_user_id_fkey;
ALTER TABLE subscription_orders ADD CONSTRAINT subscription_orders_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
