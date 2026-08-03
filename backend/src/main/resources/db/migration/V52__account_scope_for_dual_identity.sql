-- One person, two accounts: the same human can hold a USER account in the user portal and an
-- ADMIN account in the admin portal, under the same email and mobile number.
--
-- The rule stays "one user has one email and one mobile number" -- it is now scoped to the portal
-- the account belongs to, rather than being global. Without this, an administrator who also uses
-- Finora personally has to invent a second email address to sign up with.
--
-- account_scope, NOT role, is the discriminator. Roles change: a USER is promoted to ADMIN, an
-- ADMIN is demoted, and V16's RBAC lets one account hold several roles at once. Uniqueness keyed
-- on something mutable would start rejecting legitimate role changes as duplicates. account_scope
-- answers a different and stable question -- which portal is this account FOR -- and is what login
-- disambiguates on.
ALTER TABLE users ADD COLUMN IF NOT EXISTS account_scope VARCHAR(10) NOT NULL DEFAULT 'USER';

-- Backfill preserves exactly today's behaviour: every existing account keeps working, and every
-- existing admin is recognised as an admin-portal account. Derived from the legacy role column
-- (and user_roles, for accounts whose privileges live only there since V16) rather than assumed.
UPDATE users SET account_scope = 'ADMIN'
WHERE account_scope = 'USER'
  AND (role IN ('ADMIN', 'SUPER_ADMIN', 'BOOTSTRAP_ADMIN')
       OR id IN (SELECT ur.user_id FROM user_roles ur
                 JOIN roles r ON r.id = ur.role_id
                 WHERE r.name IN ('ADMIN', 'SUPER_ADMIN', 'BOOTSTRAP_ADMIN')));

ALTER TABLE users ADD CONSTRAINT ck_users_account_scope
    CHECK (account_scope IN ('USER', 'ADMIN'));

-- Replace the global uniqueness with per-scope uniqueness.
--
-- The email constraint is unnamed in V1 (`email VARCHAR(255) NOT NULL UNIQUE`), so Postgres named
-- it users_email_key by its own convention. Dropped by that generated name; IF EXISTS keeps this
-- migration safe on a database where it was already renamed or removed by hand.
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_email_key;
ALTER TABLE users DROP CONSTRAINT IF EXISTS uq_users_phone_number;

-- Case-insensitive on email, matching how registration already checks for duplicates
-- (existsByEmailIgnoreCase) -- a plain UNIQUE(email, account_scope) would let one address and the
-- same address differing only in letter case both exist in the same scope, while the application
-- believed it had already prevented exactly that.
-- A functional unique INDEX rather than a CONSTRAINT, since Postgres constraints cannot be
-- expressed over an expression.
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_email_scope
    ON users (LOWER(email), account_scope);

-- Phone is stored already-normalised (AuthService.normalizePhoneNumber), so a plain composite is
-- correct here. Partial, because phone_number is nullable and several NULLs per scope must stay
-- legal -- a plain UNIQUE would allow that too, but stating it makes the intent explicit.
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_phone_scope
    ON users (phone_number, account_scope)
    WHERE phone_number IS NOT NULL;

COMMENT ON COLUMN users.account_scope IS
    'Which portal this account belongs to: USER or ADMIN. The same person may hold one of each '
    'under the same email/phone. Login disambiguates on it; authorization does not -- that stays '
    'role-based, so an ADMIN-scope account with no admin role grants nothing.';
