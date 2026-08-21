-- D-28 PR4-C. Referral program per proposal §4 -- the third and final of D-28's three sub-PRs
-- (PR4-A entitlement architecture, PR4-B billing history scaffolding, both already merged).
--
-- referral_codes: one shareable code per user, generated lazily on first request
-- (ReferralService.myCode) rather than seeded here -- there is no natural moment before that to
-- create one.
CREATE TABLE referral_codes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL UNIQUE REFERENCES users(id),
    code VARCHAR(20) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- referrals: one row per successful redemption. referred_user_id is UNIQUE -- a user can be
-- referred at most once, by whichever code they registered with, ever. No INVITED-status row is
-- ever created by this codebase: Finora has no separate invite-by-email mechanism, so a referral
-- row's first state is REGISTERED, written the moment someone signs up with a valid code
-- (ReferralService.redeemCode). INVITED is kept in ReferralStatus for schema completeness with the
-- proposal's own lifecycle, not because anything here produces it yet.
--
-- reward is nullable: null until REWARDED (proposal §10 leaves the actual reward amount an open
-- product decision -- see ReferralService.creditReward's own doc comment for why crediting is an
-- admin-manual action, same reasoning as PR4-A's SubscriptionService.changePlan).
CREATE TABLE referrals (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    referrer_user_id UUID NOT NULL REFERENCES users(id),
    referred_user_id UUID NOT NULL UNIQUE REFERENCES users(id),
    status VARCHAR(20) NOT NULL,
    reward NUMERIC(10, 2),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_referrals_referrer_user_id ON referrals(referrer_user_id);

-- wallet_ledger: append-only (proposal §4's own reasoning -- a mutable `balance` column on User is
-- the wrong model for a financial app that already treats correctness this carefully elsewhere,
-- e.g. import recording's REQUIRES_NEW transaction handling). Balance is a computed SUM over this
-- table, never a stored field -- see WalletLedgerRepository.sumAmountByUserId.
CREATE TABLE wallet_ledger (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    amount NUMERIC(10, 2) NOT NULL,
    reason VARCHAR(30) NOT NULL,
    reference_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_wallet_ledger_user_id ON wallet_ledger(user_id);

-- Admin capability, same split as V99's SUBSCRIPTION_MANAGEMENT_VIEW/_MANAGE: viewing the referral
-- dashboard is a different grant from crediting a reward (a real money-moving action).
INSERT INTO permissions (name, description) VALUES
    ('REFERRAL_MANAGEMENT_VIEW', 'View the referral program dashboard (codes, referrals, rewards).'),
    ('REFERRAL_MANAGEMENT_MANAGE', 'Credit a referral reward to a user''s wallet.');

INSERT INTO role_permissions (role_id, permission_id)
    SELECT r.id, p.id FROM roles r, permissions p
    WHERE r.name IN ('ADMIN', 'SUPER_ADMIN') AND p.name IN ('REFERRAL_MANAGEMENT_VIEW', 'REFERRAL_MANAGEMENT_MANAGE');
