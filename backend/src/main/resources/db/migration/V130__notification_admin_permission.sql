-- Task 12: the admin notification dashboard's permission.
--
-- Read-only surface (a list plus basic send-outcome counts, per the proposal section 2.5/4) --
-- see AdminNotificationController's own doc comment for why this gets its own permission rather
-- than reusing PLATFORM_DIAGNOSTICS_VIEW.
INSERT INTO permissions (name, description) VALUES
    ('NOTIFICATION_MANAGE',
     'View the notification delivery dashboard and inspect send failures. Read-only: it grants no '
     'ability to send a notification, and no user or merchant management capability.');

-- ADMIN and SUPER_ADMIN, matching every permission added since V24. SUPER_ADMIN needs its own
-- explicit grant -- its V16 "every permission" catch-all was a one-time snapshot, not a standing
-- rule, so a new permission is not picked up by it automatically.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name IN ('ADMIN', 'SUPER_ADMIN') AND p.name = 'NOTIFICATION_MANAGE';
