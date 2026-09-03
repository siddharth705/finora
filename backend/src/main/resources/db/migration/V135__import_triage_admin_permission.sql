-- The held-imports triage queue's permission.
--
-- Its own permission rather than a reuse, because everything reachable from this surface concerns a
-- real customer's bank statement: the detail endpoint returns the raw parser error, which routinely
-- quotes the document that defeated it, and reprocess re-runs that document. PLATFORM_DIAGNOSTICS_
-- VIEW is explicitly the read-only, no-configuration-mutation visibility permission (V34);
-- LEARNING_QUEUE_MANAGE (V63) is the wrong shape too -- clearing a merchant-learning backlog should
-- not come with the ability to read statements. Same reasoning V63 itself applied.
INSERT INTO permissions (name, description) VALUES
    ('IMPORT_TRIAGE_MANAGE',
-- permissions.description is VARCHAR(255); the reasoning lives in the comment above, not here.
     'View, reprocess and resolve statements held for review after an unclassified import '
     'failure. Grants access to real user statement content; every detail view is audited.');

-- ADMIN and SUPER_ADMIN, matching every permission added since V24. SUPER_ADMIN needs its own
-- explicit grant -- its V16 "every permission" catch-all was a one-time snapshot, not a standing
-- rule, so a new permission is not picked up by it automatically.
--
-- Both inserts are mandatory: a permission with no role_permissions row grants nothing to anyone,
-- and the queue would 403 for every admin.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name IN ('ADMIN', 'SUPER_ADMIN') AND p.name = 'IMPORT_TRIAGE_MANAGE';
