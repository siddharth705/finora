-- Admin Portal, Insight Explorer (docs/proposals/reconciliation-evolution-roadmap-proposal.md,
-- Part 9). The first admin surface exposing a user's actual computed spend/category/merchant
-- amounts -- USER_VIEW (AdminUserController) only ever returns counts, and RECONCILIATION_VIEW is
-- scoped to per-transaction reconciliation verdicts, neither of which covers "how much did this
-- user spend, and on what." New capability, own permission -- same reasoning as V29's
-- RECONCILIATION_VIEW and V34's PLATFORM_DIAGNOSTICS_VIEW.
INSERT INTO permissions (name, description) VALUES
    ('INSIGHTS_EXPLORER_VIEW', 'View a user''s dashboard insights traced back to the transaction set and formula that produced them.');

-- ADMIN and SUPER_ADMIN both already see equivalent detail today via direct database/support
-- access -- granting this permission formalizes existing access rather than expanding it.
-- SUPER_ADMIN needs its own explicit grant for the same reason documented on V24/V25/V28/V29/V30/
-- V34: its V16 "every permission" catch-all is a one-time snapshot, not a standing rule.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name IN ('ADMIN', 'SUPER_ADMIN') AND p.name = 'INSIGHTS_EXPLORER_VIEW';
