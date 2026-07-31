-- Admin Portal, Reconciliation Monitor module. Reconciliation itself (ReconciliationService)
-- has always run fully automatically -- no self-service endpoint ever exposed it, by design
-- (see ReconciliationService's class comment: it runs after every import/create/edit/delete,
-- quietly, in the background). This permission gates the admin-only *observability* surface: a
-- platform-wide breakdown of reconciliation outcomes, and a per-user proxy of
-- WorkspaceDashboardService.summarize() for support staff investigating one account. There is no
-- mutation to gate here -- unlike MERCHANT_MANAGE/RULE_MANAGE, this permission never lets anyone
-- change anything, only see it.
INSERT INTO permissions (name, description) VALUES
    ('RECONCILIATION_VIEW', 'View platform-wide and per-user reconciliation and workspace health data.');

-- ADMIN gets it (operational tier, same as the rest of its permission set). SUPER_ADMIN needs its
-- own explicit grant too -- its "every permission" catch-all in V16 is a one-time snapshot INSERT,
-- not a standing rule, so it doesn't retroactively pick up permissions created afterward (same
-- reasoning documented on V24/V25/V28).
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name IN ('ADMIN', 'SUPER_ADMIN') AND p.name = 'RECONCILIATION_VIEW';
