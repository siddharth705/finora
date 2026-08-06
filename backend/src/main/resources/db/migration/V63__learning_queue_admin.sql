-- WI2 of the Import Reliability Milestone: the admin-facing merchant learning queue
-- (docs/engineering/import-reliability-milestone-design.md).
--
-- Two changes, both in service of one requirement: an operator looking at a failed learning event
-- must be able to answer "which import produced this" without opening a database client.

-- ---------------------------------------------------------------------------------------------
-- Correlation: the import SESSION, alongside the statement import V62 already records.
--
-- These are two different things and an operator needs both. statement_import is the confirmed,
-- persisted import -- it exists for every event. import_session is the staging/review session the
-- user worked through before confirming, and it exists only for imports that went through
-- ImportService.confirmSession; the direct-file confirm path never has one.
--
-- Deliberately nullable with no default, and deliberately NOT backfilled with a synthetic value.
-- An import that never had a session must read as "no session", not as a session id that resolves
-- to nothing -- an operator following that link would land on a 404 and reasonably conclude the
-- data is corrupt.
--
-- ON DELETE SET NULL, matching source_statement_import_id: an import session is deleted after its
-- 48-hour TTL (ImportSessionService), long before the events it produced are necessarily resolved.
-- Losing the link must not delete the evidence.
ALTER TABLE merchant_learning_events
    ADD COLUMN source_import_session_id UUID REFERENCES import_sessions(id) ON DELETE SET NULL;

COMMENT ON COLUMN merchant_learning_events.source_import_session_id IS
    'The staging/review session this event came from, when there was one. NULL for direct-file '
    'imports, which never have a session -- never populated with a synthetic id.';

-- Backs the queue page's "show me everything from this import" grouping, and the detail view's
-- correlation block. Partial, because the overwhelming majority of rows are COMPLETED and nobody
-- ever looks them up this way.
CREATE INDEX idx_merchant_learning_events_correlation
    ON merchant_learning_events (source_statement_import_id)
    WHERE status IN ('PENDING', 'PROCESSING', 'FAILED');

-- ---------------------------------------------------------------------------------------------
-- LEARNING_QUEUE_MANAGE.
--
-- Its own permission rather than a reuse, for the reason V61 records when it introduced
-- ENGINE_ANALYSIS_RUN: PLATFORM_DIAGNOSTICS_VIEW is explicitly the read-only operational-visibility
-- permission ("no configuration-mutation power"), and this surface retries work that mutates a
-- user's learning distribution. Gating an action behind a view permission quietly undoes the
-- separation V34 established, and does it invisibly -- everyone granted "view diagnostics" would
-- silently also be able to replay learning events.
--
-- Not MERCHANT_MANAGE either, even though the effect lands on merchant learning. That permission
-- is about curating a user's merchants; this is about operating a queue, and an engineer who needs
-- to clear a stuck backlog should not have to be handed the ability to merge and rename a
-- customer's merchants to do it.
INSERT INTO permissions (name, description) VALUES
    ('LEARNING_QUEUE_MANAGE',
     'View the merchant learning queue and retry or resolve failed events. Does not grant any '
     'other merchant or user management capability.');

-- ADMIN and SUPER_ADMIN, matching every permission added since V24. SUPER_ADMIN needs its own
-- explicit grant for the reason documented on V24/V25/V28/V29/V30/V34/V61: its V16 "every
-- permission" catch-all was a one-time snapshot, not a standing rule, so a new permission is not
-- picked up by it automatically.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name IN ('ADMIN', 'SUPER_ADMIN') AND p.name = 'LEARNING_QUEUE_MANAGE';
