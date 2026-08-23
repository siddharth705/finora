-- D-28's billing/wallet/referral tables (V99-V101) each gave user_id a plain REFERENCES users(id)
-- with no ON DELETE behavior, which defaults to NO ACTION -- discovered by
-- e2e/tests/workflow/isolation.spec.ts's "records which user_id foreign keys still block account
-- deletion" diagnostic, which encodes the project's rule: every user_id FK must be either CASCADE
-- (the row belongs to the user, should go with them) or SET NULL (an audit entry that must
-- survive, e.g. password_history).
--
-- All four belong to the user, not to an audit trail that must outlive them:
--   - payments/subscriptions are already hard-deleted directly by
--     AccountPurgeSweepService.purgeOne (PaymentRepository/SubscriptionRepository
--     .hardDeleteByUserId) -- CASCADE just makes that the DB's own guarantee too, matching the
--     V100/V99 migration comments' own stated intent, not changing it.
--   - referral_codes/wallet_ledger are likewise deleted directly there
--     (ReferralCodeRepository/WalletLedgerRepository.deleteByUserId). A referral_codes row is the
--     deleted user's own shareable code; it has no bearing on the `referrals` redemption record
--     left behind for whoever used it (referrals.referrer_user_id/referred_user_id are separate
--     FKs straight to users, not to referral_codes, and are already purged independently by
--     ReferralRepository -- see AccountPurgeSweepService's own comment).
ALTER TABLE payments DROP CONSTRAINT payments_user_id_fkey;
ALTER TABLE payments ADD CONSTRAINT payments_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE subscriptions DROP CONSTRAINT subscriptions_user_id_fkey;
ALTER TABLE subscriptions ADD CONSTRAINT subscriptions_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE referral_codes DROP CONSTRAINT referral_codes_user_id_fkey;
ALTER TABLE referral_codes ADD CONSTRAINT referral_codes_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE wallet_ledger DROP CONSTRAINT wallet_ledger_user_id_fkey;
ALTER TABLE wallet_ledger ADD CONSTRAINT wallet_ledger_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
