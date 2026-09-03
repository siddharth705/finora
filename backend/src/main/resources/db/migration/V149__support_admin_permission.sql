-- The support desk's permission.
--
-- Its own permission rather than a reuse, for the same reason V135 gave IMPORT_TRIAGE_MANAGE its
-- own: everything reachable from this surface is a real customer's free-text description of a
-- problem with their own money, which routinely quotes balances, merchants and amounts, plus any
-- file they attached to illustrate it. PLATFORM_DIAGNOSTICS_VIEW is explicitly the read-only,
-- no-configuration-mutation visibility permission (V34), and this surface both mutates state and
-- exposes customer content -- neither fits.

INSERT INTO permissions (name, description) VALUES
    ('SUPPORT_MANAGE',
-- permissions.description is VARCHAR(255); the reasoning lives in the comment above, not here.
     'View and work support tickets: change status, claim, add internal notes, download user '
     'attachments, and read product feedback. Grants access to customer-submitted content.');

-- ADMIN and SUPER_ADMIN, matching every permission added since V24. SUPER_ADMIN needs its own
-- explicit grant -- its V16 "every permission" catch-all was a one-time snapshot, not a standing
-- rule, so a new permission is not picked up by it automatically.
--
-- Both inserts are mandatory: a permission with no role_permissions row grants nothing to anyone,
-- and every support screen would 403 for every admin.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name IN ('ADMIN', 'SUPER_ADMIN') AND p.name = 'SUPPORT_MANAGE';

-- REMINDER FOR WHOEVER WIRES THE PORTAL
-- -------------------------------------
-- This migration is one of FOUR places a new admin permission has to appear. The other three are
-- frontend and easy to miss:
--   * admin-portal/src/components/Sidebar.tsx        -- the nav entry
--   * admin-portal/src/context/AdminAuthContext.tsx  -- the portal-ENTRY allowlist
--   * admin-portal/src/App.tsx                       -- the <RequirePermission> route wrapper
-- The allowlist is the one that bites: a role holding ONLY this permission cannot get past the
-- portal-entry check to reach the single section it is entitled to. That bug has already shipped
-- once here, with RELATIONSHIP_MANAGE.
