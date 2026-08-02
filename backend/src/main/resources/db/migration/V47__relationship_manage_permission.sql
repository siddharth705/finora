-- Admin Portal, Relationships (family/friend/own-account tagging). Relationship CRUD/merge
-- previously lived only as a self-service endpoint (RelationshipController) -- that controller is
-- being retired in favor of admin-only management on a specific user's behalf, same shift already
-- made for merchants (MERCHANT_MANAGE) and rules (RULE_MANAGE). This permission gates the new
-- AdminUserRelationshipController.
INSERT INTO permissions (name, description) VALUES
    ('RELATIONSHIP_MANAGE', 'Manage relationship (family/friend/own-account) tagging on behalf of a specific user.');

-- ADMIN gets it (operational tier, same as the rest of its permission set). SUPER_ADMIN needs its
-- own explicit grant too -- its "every permission" catch-all in V16 is a one-time snapshot INSERT,
-- not a standing rule, so it doesn't retroactively pick up permissions created afterward (same
-- reasoning documented on V25__rule_manage_permission.sql/V28__merchant_manage_permission.sql).
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name IN ('ADMIN', 'SUPER_ADMIN') AND p.name = 'RELATIONSHIP_MANAGE';
