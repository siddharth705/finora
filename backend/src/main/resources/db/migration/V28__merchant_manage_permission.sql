-- Admin Portal, Merchant Intelligence module. Merchant rename/merge/audit already existed as
-- self-service endpoints (MerchantController, docs/financial-intelligence-engine-spec.md
-- Section 5) -- this permission gates the *admin-side* proxy of those same operations (acting on
-- a specific user's merchants from the admin console, same "support-assisted" pattern as
-- TRANSACTION_DELETE on AdminTransactionController) plus the new platform-wide aggregate stats
-- view, which has no self-service equivalent at all.
INSERT INTO permissions (name, description) VALUES
    ('MERCHANT_MANAGE', 'View platform-wide merchant intelligence, and manage merchants on behalf of a specific user.');

-- ADMIN gets it (operational tier, same as the rest of its permission set). SUPER_ADMIN needs its
-- own explicit grant too -- its "every permission" catch-all in V16 is a one-time snapshot INSERT,
-- not a standing rule, so it doesn't retroactively pick up permissions created afterward (same
-- reasoning documented on V24__admin_platform_stats_permission.sql and V25__rule_manage_permission.sql).
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name IN ('ADMIN', 'SUPER_ADMIN') AND p.name = 'MERCHANT_MANAGE';
