-- One optional file per support ticket, stored as bytes on a child table.
--
-- WHY BYTEA HERE AND NOT StatementStorage
-- ---------------------------------------
-- com.finora.imports.storage is a complete, built object-storage layer, so reusing it is the
-- obvious move -- and it would be wrong at this size. That layer is content-addressed and its
-- objects are deliberately SHARED: identical bytes resolve to one object, so deleting a row must
-- never delete its object, and reclamation is handled solely by StatementStorageSweepService, which
-- proves the absence of any live reference across three tables before deleting anything. That third
-- table was added only after a real FAILED import_jobs row in production turned out to own an
-- object nothing else referenced (BH-017).
--
-- Routing support attachments through it would introduce a FOURTH source of references the sweep
-- does not know about. It would then reclaim objects a ticket still points at, and the failure is
-- silent and delayed -- a download breaking days later with nothing connecting it to the cause.
--
-- Support attachments need none of what that layer provides: small, low-volume, never deduplicated,
-- never re-imported, never shared between rows. If volume ever justifies moving them, the
-- prerequisite is explicit -- extend StatementStorageSweepService's reference count to include this
-- table FIRST, in the same change.

CREATE TABLE support_ticket_attachments (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- This CASCADE DOES fire, unlike the one on support_tickets.user_id -- but only because Phase 6
    -- will delete the ticket rows explicitly. It cascades off a repository delete of the parent,
    -- not off any deletion of the users row (purgeOne anonymizes that one; see V145's own note).
    -- Without this FK the purge would fail on a foreign key and take the whole account-deletion
    -- path down -- the failure mode V144 records for held_statements.
    ticket_id     UUID NOT NULL REFERENCES support_tickets(id) ON DELETE CASCADE,

    filename      VARCHAR(120) NOT NULL,
    content_type  VARCHAR(100) NOT NULL,
    size_bytes    BIGINT NOT NULL,
    sha256_hash   VARCHAR(64) NOT NULL,
    content       BYTEA NOT NULL,

    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at    TIMESTAMPTZ,
    version       BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_support_ticket_attachments_by_ticket ON support_ticket_attachments (ticket_id);

COMMENT ON TABLE support_ticket_attachments IS
    'Attachment bytes for a support ticket. A child table rather than a column on support_tickets '
    'because the download URL carries an attachment id (/attachments/{attachmentId}) that a single '
    'column cannot supply. The v1 UI accepts exactly one file; only the storage shape is plural, '
    'which makes the eventual single-to-multiple change a validation change rather than a backfill '
    'against a live table.';

COMMENT ON COLUMN support_ticket_attachments.filename IS
    'Bounded at 120 chars, matching StatementUpload''s own cap and for the same reason: this value '
    'is attacker-chosen, persisted, rendered in an admin list, and echoed in a Content-Disposition '
    'header.';

COMMENT ON COLUMN support_ticket_attachments.content_type IS
    'The validated type, not whatever the client claimed. PDF, PNG, JPEG and TXT only. Browsers and '
    'mobile clients disagree wildly about the content type they attach to a file, so the check that '
    'actually decides is magic bytes where the format has them -- see StatementUpload''s reasoning.';

COMMENT ON COLUMN support_ticket_attachments.content IS
    'Never served from a public or signed URL. Every download goes through an authenticated endpoint '
    'that re-derives ownership per request, the same posture as the statement download path.';
