-- Admin Portal, Platform Diagnostics + System Health. Previously both gated by SYSTEM_SETTINGS,
-- which today also gates PlatformSettingsController's PUT (change registrations/lockout policy)
-- and AdminFeatureFlagController's PUT (toggle a flag platform-wide) -- meaning anyone who could
-- merely LOOK at diagnostics/health also necessarily had the power to mutate platform-wide
-- config, with no way to grant one without the other. Separate permission for the read-only
-- operational-visibility surface specifically (Platform Diagnostics + System Health -- NOT
-- PlatformSettingsController or AdminFeatureFlagController, which remain SYSTEM_SETTINGS since
-- mutating config is exactly what that permission name describes).
--
-- Only 3 roles exist today (SUPER_ADMIN, ADMIN, USER -- see V16), none matching the finer-grained
-- "Technical Support"/"DevOps Engineer" style roles this permission is ultimately meant to
-- support -- but ROLE_MANAGE + PERMISSION_MANAGE already let an admin create exactly such a
-- custom role today, granted only this permission, without also handing them SYSTEM_SETTINGS's
-- mutation power. That's the actual value this migration unlocks, not a role that exists yet.
INSERT INTO permissions (name, description) VALUES
    ('PLATFORM_DIAGNOSTICS_VIEW', 'View read-only platform diagnostics and system health (no configuration-mutation power -- see SYSTEM_SETTINGS for that).');

-- ADMIN and SUPER_ADMIN both already have SYSTEM_SETTINGS and therefore already see this data
-- today -- granting them this new permission too keeps that access unchanged rather than
-- shrinking it. SUPER_ADMIN needs its own explicit grant here for the same reason documented on
-- V24/V25/V28/V29/V30: its V16 "every permission" catch-all is a one-time snapshot, not a
-- standing rule.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name IN ('ADMIN', 'SUPER_ADMIN') AND p.name = 'PLATFORM_DIAGNOSTICS_VIEW';
