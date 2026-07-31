-- First-run setup: replaces "grant the temporary installer account SUPER_ADMIN" with a
-- least-privilege BOOTSTRAP_ADMIN identity that can only initialize the platform, then gets
-- locked (see SetupService). Single boolean flag on the existing platform_settings singleton
-- row (V27__platform_settings.sql) rather than a fresh table or a "scan users for SUPER_ADMIN"
-- check on every startup -- BootstrapService reads this one column once per boot.
ALTER TABLE platform_settings ADD COLUMN setup_completed BOOLEAN NOT NULL DEFAULT false;

INSERT INTO roles (name, description) VALUES
    ('BOOTSTRAP_ADMIN', 'One-time installation identity, created automatically on first boot and '
        || 'locked forever the moment setup completes. Can only initialize the platform -- '
        || 'nothing else.');

INSERT INTO permissions (name, description) VALUES
    ('SYSTEM_INITIALIZE', 'Complete first-run platform setup: create the first SUPER_ADMIN, lock '
        || 'the bootstrap account, and mark setup complete. Not granted to any other role, '
        || 'including SUPER_ADMIN -- this permission has no purpose once setup is done.');

-- Deliberately exclusive to BOOTSTRAP_ADMIN -- unlike every other permission added in past
-- migrations (which also backfill ADMIN/SUPER_ADMIN, see V25__rule_manage_permission.sql's doc
-- comment), SYSTEM_INITIALIZE only ever means anything for the one account whose entire purpose
-- is to stop existing after using it once.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name = 'BOOTSTRAP_ADMIN' AND p.name = 'SYSTEM_INITIALIZE';
