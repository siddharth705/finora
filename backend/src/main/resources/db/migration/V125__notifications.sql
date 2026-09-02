-- The notification outbox. NotificationService.request() writes a row here inside the caller's
-- own transaction; NotificationDispatcher claims and delivers it. Durable by construction: a
-- crash between "the event happened" and "the user was told" leaves a replayable row.

CREATE TABLE notifications (
    id                UUID PRIMARY KEY,
    user_id           UUID NOT NULL REFERENCES users(id),
    notification_key  VARCHAR(200) NOT NULL UNIQUE,
    type              VARCHAR(64) NOT NULL,
    category          VARCHAR(32) NOT NULL,
    channel           VARCHAR(16) NOT NULL,
    priority          VARCHAR(16) NOT NULL,
    status            VARCHAR(16) NOT NULL DEFAULT 'CREATED',
    title             VARCHAR(300) NOT NULL,
    message           VARCHAR(2000) NOT NULL,
    attempt_count     INTEGER NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMPTZ NOT NULL,
    last_error        VARCHAR(2000),
    sent_at           TIMESTAMPTZ,
    read_at           TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL
);

-- Partial index scoped to the claimable subset, so it stays proportional to the live backlog
-- rather than to every notification ever sent.
CREATE INDEX idx_notifications_claimable
    ON notifications (next_attempt_at)
    WHERE status IN ('CREATED', 'QUEUED', 'RETRYING');

CREATE INDEX idx_notifications_user_created ON notifications (user_id, created_at DESC);

COMMENT ON TABLE notifications IS
    'Transactional outbox for user notifications. Written in the caller''s transaction, delivered '
    'asynchronously by NotificationDispatcher.';
COMMENT ON COLUMN notifications.notification_key IS
    'Caller-supplied deterministic idempotency key, e.g. IMPORT_READY_{jobId}. UNIQUE so a backend '
    'retry or a redelivered job cannot produce a duplicate send -- for financial events a duplicate '
    'is a trust problem, not just noise.';
COMMENT ON COLUMN notifications.status IS
    'CREATED/QUEUED/PROCESSING/SENT/FAILED/RETRYING/DEAD_LETTER. Deliberately no DELIVERED or READ: '
    'no provider webhook exists to populate them truthfully (see the notification platform proposal '
    'section 2.5). Plain VARCHAR, not a native enum, matching every other status column here.';
COMMENT ON COLUMN notifications.read_at IS
    'Client-reported in-app open time for a future inbox. Unrelated to provider delivery '
    'confirmation; nothing populates it in v1.';
