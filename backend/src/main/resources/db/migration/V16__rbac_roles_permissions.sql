-- Database-driven RBAC (docs/engineering-directive-phase1.md, Priority 2).
--
-- Replaces the single users.role string as the *sole* authorization signal with a proper
-- Role -> Permission model. users.role is NOT dropped here -- see User.java's class comment and
-- AuthorizationService for why: the legacy column is still honored as an implicit single-role
-- assignment, so every user/test that only ever set that column keeps exactly the access it
-- always had, with zero re-seeding required. New code should prefer explicit user_roles rows
-- (which support multiple roles per user); the legacy column is a zero-downtime migration path,
-- not a long-term parallel system to keep maintaining.

CREATE TABLE roles (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(50)  NOT NULL UNIQUE,
    description VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE permissions (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(50)  NOT NULL UNIQUE,
    description VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE role_permissions (
    role_id       UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission_id UUID NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE user_roles (
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id    UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, role_id)
);

CREATE INDEX idx_user_roles_user_id ON user_roles(user_id);

-- Seed roles. SUPER_ADMIN is reserved -- nothing in the application grants it automatically;
-- it exists so there's always a documented top rung above ADMIN for managing the RBAC system
-- itself, assigned manually/out-of-band when the platform actually needs it.
INSERT INTO roles (name, description) VALUES
    ('SUPER_ADMIN', 'Reserved. Full platform access, including managing the role/permission system itself.'),
    ('ADMIN', 'Operational administrator: manages users, accounts, banks, and views system-wide audit trails.'),
    ('USER', 'Standard authenticated user, scoped to their own data via resource ownership checks.');

-- Seed the initial permission set from the engineering directive.
INSERT INTO permissions (name, description) VALUES
    ('USER_VIEW', 'View other users'' account/profile details.'),
    ('USER_CREATE', 'Create a user account on behalf of someone else (e.g. support-assisted signup).'),
    ('USER_UPDATE', 'Update another user''s profile or settings.'),
    ('USER_DELETE', 'Delete or deactivate another user''s account.'),
    ('ACCOUNT_CREATE', 'Create a financial account on behalf of another user.'),
    ('ACCOUNT_UPDATE', 'Update another user''s financial account.'),
    ('ACCOUNT_DELETE', 'Delete another user''s financial account.'),
    ('TRANSACTION_IMPORT', 'Trigger a statement import on behalf of another user.'),
    ('TRANSACTION_DELETE', 'Delete another user''s transactions.'),
    ('REPORT_VIEW', 'View reports.'),
    ('REPORT_EXPORT', 'Export report data.'),
    ('BANK_MANAGE', 'Manage the shared bank registry (add/edit supported banks).'),
    ('ROLE_MANAGE', 'Create/edit roles and assign roles to users.'),
    ('PERMISSION_MANAGE', 'Create/edit permissions and assign permissions to roles.'),
    ('SYSTEM_SETTINGS', 'Manage system-wide configuration.'),
    ('AUDIT_VIEW', 'View audit logs across users.');

-- ADMIN: every operational permission, excluding the ability to manage the RBAC system itself
-- (ROLE_MANAGE / PERMISSION_MANAGE) or create new user accounts (USER_CREATE) -- granting
-- someone the power to grant permissions, or to mint new accounts, is its own elevation and is
-- reserved for SUPER_ADMIN.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name = 'ADMIN' AND p.name IN (
    'USER_VIEW', 'USER_UPDATE', 'USER_DELETE',
    'ACCOUNT_CREATE', 'ACCOUNT_UPDATE', 'ACCOUNT_DELETE',
    'TRANSACTION_IMPORT', 'TRANSACTION_DELETE',
    'REPORT_VIEW', 'REPORT_EXPORT', 'BANK_MANAGE', 'SYSTEM_SETTINGS', 'AUDIT_VIEW'
);

-- SUPER_ADMIN: every permission that exists, including managing roles/permissions and creating users.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p WHERE r.name = 'SUPER_ADMIN';

-- USER: baseline permissions for a standard authenticated user. Most of what a standard user can
-- do is already enforced through resource ownership (every account/transaction/goal/budget query
-- is scoped to CurrentUser.id(), not gated by a named permission) -- these are the two
-- permission-gated actions a plain USER has today.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name = 'USER' AND p.name IN ('REPORT_VIEW', 'REPORT_EXPORT');

-- Backfill: every existing user's legacy `role` string becomes an explicit user_roles row too,
-- so admin tooling built against user_roles (e.g. "list this user's roles") sees real data from
-- day one instead of an empty table until someone happens to re-save each user.
INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM users u JOIN roles r ON r.name = u.role
ON CONFLICT DO NOTHING;
