-- Append-only history of delivery attempts. The notifications row holds current state; this holds
-- how it got there, which is what an admin needs when answering "why did this fail".

CREATE TABLE notification_logs (
    id              UUID PRIMARY KEY,
    notification_id UUID NOT NULL REFERENCES notifications(id) ON DELETE CASCADE,
    provider        VARCHAR(64) NOT NULL,
    response        VARCHAR(2000),
    success         BOOLEAN NOT NULL,
    attempt         INTEGER NOT NULL,
    timestamp       TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_notification_logs_notification
    ON notification_logs (notification_id, timestamp DESC);

CREATE INDEX idx_notification_logs_failures
    ON notification_logs (timestamp DESC)
    WHERE success = FALSE;

COMMENT ON TABLE notification_logs IS
    'One row per delivery attempt per provider. Append-only.';
COMMENT ON COLUMN notification_logs.success IS
    'The provider''s synchronous API call returned OK. NOT a delivery confirmation -- no provider '
    'webhook exists in this codebase, which is also why notifications has no DELIVERED state.';
COMMENT ON COLUMN notification_logs.response IS
    'Provider response or error detail. Redacted (emails/phones/tokens replaced with placeholders)'
    ' and truncated to 2000 chars by NotificationLog.of before this row is ever written -- never '
    'store a raw credential or an unmasked recipient here.';
