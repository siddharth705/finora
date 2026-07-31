-- New permission for the admin portal's platform-wide stats overview (signups, transaction
-- volume, import volume across every user) -- deliberately distinct from AUDIT_VIEW (per-user
-- audit trail) and SYSTEM_SETTINGS (operational config/health), since none of those three imply
-- the other two: a support agent investigating one user's audit trail shouldn't automatically
-- see aggregate platform metrics, and vice versa.
INSERT INTO permissions (name, description) VALUES
    ('PLATFORM_STATS_VIEW', 'View aggregate platform-wide usage statistics across all users.');

-- Same operational tier as the rest of ADMIN's permission set (see V16).
--
-- SUPER_ADMIN's "every permission" grant in V16 is a one-time INSERT...SELECT snapshot taken at
-- that migration's run time, not a standing rule -- it does not automatically pick up permissions
-- created afterward, so SUPER_ADMIN needs its own explicit grant here too, or it would end up
-- with strictly fewer permissions than ADMIN for this one capability.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name IN ('ADMIN', 'SUPER_ADMIN') AND p.name = 'PLATFORM_STATS_VIEW';
