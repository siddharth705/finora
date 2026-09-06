-- V125's notifications table gave user_id a plain REFERENCES users(id) with no ON DELETE
-- behavior, which defaults to NO ACTION -- caught by e2e/tests/workflow/isolation.spec.ts's
-- "records which user_id foreign keys still block account deletion" diagnostic, the same one
-- V106 fixed for the D-28 billing/wallet/referral tables. Same fix, same reasoning: a
-- notification belongs to the user it was sent to, not to an audit trail that must outlive them.
--
-- CASCADE alone would never fire in production -- AccountPurgeSweepService.purgeOne never issues
-- a raw DELETE FROM users, it anonymizes the row instead -- so this migration only makes the DB's
-- own guarantee match what purgeOne is separately being given its own explicit
-- NotificationRepository.deleteByUserId(userId) call to do, the same belt-and-suspenders pattern
-- V106's own comment already established for payments/subscriptions/referral_codes/wallet_ledger.
ALTER TABLE notifications DROP CONSTRAINT notifications_user_id_fkey;
ALTER TABLE notifications ADD CONSTRAINT notifications_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
