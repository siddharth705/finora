-- Notification copy, per type and channel. Centralized so wording is reviewable in one place
-- instead of hardcoded across every calling service.

CREATE TABLE notification_templates (
    id             UUID PRIMARY KEY,
    type           VARCHAR(64) NOT NULL,
    channel        VARCHAR(16) NOT NULL,
    title_template VARCHAR(300) NOT NULL,
    body_template  VARCHAR(2000) NOT NULL,
    active         BOOLEAN NOT NULL DEFAULT TRUE
);

-- At most one ACTIVE template per (type, channel) -- enforced by the database, not just
-- service-layer discipline. Deliberately partial rather than a plain UNIQUE (type, channel,
-- active): a 3-column constraint with active as part of the key only blocks a SECOND *active* row,
-- but does nothing to stop a second *retired* one -- two rows both holding (type, channel, false)
-- satisfy that constraint just fine, so it would tolerate exactly one historical revision of any
-- template, ever, and then fail on the next. Retiring copy means flipping active to false on the
-- old row (never deleting it, so past renders stay attributable to the copy they used) and
-- inserting a new active row for the same (type, channel) -- a partial index scoped to WHERE
-- active is what actually allows unlimited retired history while still guaranteeing
-- DatabaseTemplateRenderer's findByTypeAndChannelAndActiveTrue never finds more than one row.
CREATE UNIQUE INDEX idx_notification_templates_active
    ON notification_templates (type, channel)
    WHERE active = true;

COMMENT ON TABLE notification_templates IS
    'Notification copy, per type and channel. English-only in v1. A language column can be added '
    'later without breaking this schema; i18n is a separate initiative (this app has no message '
    'bundles or locale resolver today).';
COMMENT ON COLUMN notification_templates.type IS
    'Matches com.finora.notification.domain.NotificationType. A type with no active template row '
    'here cannot be rendered -- DatabaseTemplateRenderer throws and NotificationService suppresses '
    'the send rather than failing the caller''s transaction.';
COMMENT ON COLUMN notification_templates.channel IS
    'Wording differs by channel -- push is terse, email has room.';
COMMENT ON COLUMN notification_templates.title_template IS
    'Plain {{placeholder}} substitution, not a templating engine -- see '
    'DatabaseTemplateRenderer.substitute. An unmatched placeholder is left visible in the rendered '
    'output rather than emitting a blank or the literal string "null".';
COMMENT ON COLUMN notification_templates.active IS
    'idx_notification_templates_active (a partial UNIQUE index WHERE active = true) guarantees at '
    'most one active row per (type, channel); retired rows are unlimited. Retiring old copy means '
    'deactivating the old row, not deleting it, so past renders stay attributable to the copy '
    'they used.';

-- Seed the two types NotificationType declares. A type with no template row cannot be delivered,
-- so these ship together with the enum values rather than being configured post-deploy. Only the
-- channels each type is actually requested on (see ImportService / PasswordChangeService callers)
-- get a row; SMS has no copy here because neither caller requests it today -- add it if a caller
-- starts requesting NotificationChannel.SMS for one of these types.
INSERT INTO notification_templates (id, type, channel, title_template, body_template) VALUES
    (gen_random_uuid(), 'IMPORT_STATEMENT_READY', 'EMAIL',
     'Your {{bank}} statement is ready',
     'Good news -- we finished processing your {{bank}} statement and imported it successfully. '
     'You can view your transactions in Fynora now.'),
    (gen_random_uuid(), 'IMPORT_STATEMENT_READY', 'PUSH',
     'Statement ready',
     'Your {{bank}} statement has been imported.'),
    (gen_random_uuid(), 'PASSWORD_CHANGED', 'EMAIL',
     'Your password was changed',
     'The password on your Fynora account was just changed. If this was not you, reset your '
     'password immediately and contact Fynora support.'),
    (gen_random_uuid(), 'PASSWORD_CHANGED', 'PUSH',
     'Password changed',
     'Your Fynora password was just changed.');
