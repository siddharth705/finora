-- Per-user notification opt-in/out. DatabaseNotificationPreferenceResolver reads this table; an
-- absent row means the category default (see below).

CREATE TABLE notification_preferences (
    id       UUID PRIMARY KEY,
    user_id  UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    category VARCHAR(32) NOT NULL,
    channel  VARCHAR(16) NOT NULL,
    enabled  BOOLEAN NOT NULL,
    UNIQUE (user_id, category, channel)
);

-- Plain multi-column UNIQUE, not a partial index: unlike the mutable-flag bug Task 7 shipped
-- (a "retired" boolean baked into a three-column UNIQUE, which made a second retirement
-- impossible), user_id/category/channel here are the row's whole identity and never change after
-- insert -- only the unrelated `enabled` column is mutated by a later opt-in/opt-out. There is no
-- mutable column inside this uniqueness triple to trap a future update against.

CREATE INDEX idx_notification_preferences_user ON notification_preferences (user_id);

COMMENT ON TABLE notification_preferences IS
    'Per-user opt-in/out. An absent row means the category default: MARKETING is opt-in, every '
    'other category is opt-out. Account-status suppression (a stepped-away account silences '
    'FINANCIAL/MARKETING regardless of what this table says) is enforced in application code, in '
    'DatabaseNotificationPreferenceResolver, not here -- this table only ever records what the '
    'user themselves asked for.';
COMMENT ON COLUMN notification_preferences.category IS
    'SECURITY rows are never consulted -- security notifications are forcibly on, because a user '
    'who silenced them could not be told their password changed. Rows may exist for SECURITY '
    'without effect.';
