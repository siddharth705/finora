-- Global (system-wide) category rules were seed-data-only until now (see RuleService's class
-- comment: "authoring global rules is migration-seeded data for this milestone... a RULE_MANAGE-
-- gated admin console is a fast-follow"). This is that fast-follow's permission.
INSERT INTO permissions (name, description) VALUES
    ('RULE_MANAGE', 'Create, edit, and disable GLOBAL-scope category rules that apply to every user.');

-- ADMIN gets it (operational tier, same as the rest of its permission set). SUPER_ADMIN needs its
-- own explicit grant too -- its "every permission" catch-all in V16 is a one-time snapshot INSERT,
-- not a standing rule, so it doesn't retroactively pick up permissions created afterward (same
-- reasoning documented on V24__admin_platform_stats_permission.sql).
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name IN ('ADMIN', 'SUPER_ADMIN') AND p.name = 'RULE_MANAGE';
