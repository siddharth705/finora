-- Admin Portal, Platform Analytics module. Platform-wide spend aggregates (top categories, top
-- merchants) that have no self-service equivalent -- AnalyticsController's existing views are
-- always scoped to CurrentUser, by design (spec §5.7). Separate permission from
-- PLATFORM_STATS_VIEW (AdminStatsController) on purpose: that one is basic usage counts (users,
-- accounts, transactions), simple enough to show on the main admin Dashboard; this one is
-- financial-intelligence-specific spend analytics, a distinct capability someone could reasonably
-- be granted one of without the other.
INSERT INTO permissions (name, description) VALUES
    ('PLATFORM_ANALYTICS_VIEW', 'View platform-wide financial intelligence analytics (spend by category/merchant across every user).');

-- ADMIN gets it (operational tier, same as the rest of its permission set). SUPER_ADMIN needs its
-- own explicit grant too -- its "every permission" catch-all in V16 is a one-time snapshot INSERT,
-- not a standing rule, so it doesn't retroactively pick up permissions created afterward (same
-- reasoning documented on V24/V25/V28/V29).
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name IN ('ADMIN', 'SUPER_ADMIN') AND p.name = 'PLATFORM_ANALYTICS_VIEW';
