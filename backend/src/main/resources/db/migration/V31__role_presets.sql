-- Admin Portal Phase 8 (Permissions Strategy) -- concrete role presets beyond the original
-- SUPER_ADMIN/ADMIN/USER trio from V16. Every admin-portal permission that exists as of this
-- migration (V16/V24/V25/V26/V28/V29/V30) is real and already enforced by a @PreAuthorize
-- somewhere -- these presets are just sensibly-scoped bundles of that existing, already-working
-- permission set for four common support/ops job functions, not new capabilities.

INSERT INTO roles (name, description) VALUES
    ('SUPPORT', 'Front-line support: can look up and assist a specific user (view/update their profile, manage their accounts and statement imports, fix their merchants/rules) without account-deletion or system-config power.'),
    ('FINANCE', 'Read-only visibility into platform-wide financial reporting: reports, platform stats, spend analytics, and reconciliation health -- no user data mutation of any kind.'),
    ('OPS', 'Platform operations: system configuration, the bank registry, global rules, merchant catalog upkeep, and full observability -- deliberately excludes per-user PII mutation (no USER_UPDATE/USER_DELETE/ACCOUNT_*).'),
    ('READ_ONLY', 'Pure observer: can view users, audit history, reports, and every platform-wide stats/analytics surface, but cannot create, update, delete, or manage anything.');

-- SUPPORT: everything a support agent needs to help one specific user, short of deleting their
-- account or minting new ones (those stay ADMIN+/SUPER_ADMIN-only, same reasoning V16 gives for
-- excluding USER_CREATE from ADMIN).
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name = 'SUPPORT' AND p.name IN (
    'USER_VIEW', 'USER_UPDATE',
    'ACCOUNT_CREATE', 'ACCOUNT_UPDATE',
    'TRANSACTION_IMPORT',
    'AUDIT_VIEW', 'MERCHANT_MANAGE', 'RULE_MANAGE', 'RECONCILIATION_VIEW'
);

-- FINANCE: read-only across every reporting/analytics surface, nothing that touches a user's own
-- account or data.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name = 'FINANCE' AND p.name IN (
    'REPORT_VIEW', 'REPORT_EXPORT', 'PLATFORM_STATS_VIEW', 'PLATFORM_ANALYTICS_VIEW',
    'RECONCILIATION_VIEW', 'AUDIT_VIEW'
);

-- OPS: platform configuration and the shared reference data (banks, global rules, merchant
-- catalog) plus full observability -- no USER_UPDATE/USER_DELETE/ACCOUNT_* (that's SUPPORT's job,
-- not this role's).
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name = 'OPS' AND p.name IN (
    'SYSTEM_SETTINGS', 'BANK_MANAGE', 'RULE_MANAGE', 'MERCHANT_MANAGE',
    'PLATFORM_STATS_VIEW', 'PLATFORM_ANALYTICS_VIEW', 'RECONCILIATION_VIEW', 'AUDIT_VIEW'
);

-- READ_ONLY: view-only across the board -- every _VIEW permission that exists, and nothing else.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name = 'READ_ONLY' AND p.name IN (
    'USER_VIEW', 'AUDIT_VIEW', 'REPORT_VIEW', 'PLATFORM_STATS_VIEW', 'PLATFORM_ANALYTICS_VIEW',
    'RECONCILIATION_VIEW'
);
