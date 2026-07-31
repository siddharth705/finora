-- Real platform-wide configuration (SYSTEM_SETTINGS previously only gated a read-only health
-- check -- see AdminSystemController -- despite that permission's own V16 description promising
-- "manage system-wide configuration"). Classic singleton-row table: id is pinned to 1 by the
-- CHECK constraint, so there is exactly one settings row for the whole platform, ever -- every
-- reader/writer just asks for id=1 rather than needing a "find the one row" query.
--
-- Defaults exactly match the hardcoded values AuthService used before this migration
-- (MAX_FAILED_LOGIN_ATTEMPTS=5, LOCKOUT_DURATION_MINUTES=15) -- applying this migration changes
-- nothing about current behavior until an admin actually edits a value.
CREATE TABLE platform_settings (
    id                          SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    registrations_enabled       BOOLEAN NOT NULL DEFAULT true,
    max_failed_login_attempts   INTEGER NOT NULL DEFAULT 5,
    lockout_duration_minutes    INTEGER NOT NULL DEFAULT 15,
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO platform_settings (id) VALUES (1);
