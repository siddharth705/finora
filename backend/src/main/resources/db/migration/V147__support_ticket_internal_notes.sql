-- Admin-only operational notes on a ticket -- "reproduced on Android 1.3.7", "waiting on the next
-- deploy", "linked to bug #452". Append-only: no update endpoint, no delete endpoint.
--
-- WHY A SEPARATE TABLE, NOT A COLUMN AND NOT A MESSAGE TYPE
-- ---------------------------------------------------------
-- A single mutable admin_note column on support_tickets carries no author and silently overwrites
-- the previous note. The portal is multi-admin (V52's account_scope IN ('USER','ADMIN')), so that
-- loses exactly the information the field exists to capture.
--
-- Folding these into a customer-visible message table is the Zendesk public/internal comment shape,
-- whose well-known failure mode is one missing filter rendering an admin's internal notes inside the
-- user's own ticket view. A separate table cannot fail that way: there is no query path from any
-- user-facing endpoint to it at all. Given this app handles financial data, that structural
-- guarantee is worth one extra table.

CREATE TABLE support_ticket_internal_notes (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- CASCADE on the ticket, so account deletion removes these with the ticket that owns them.
    ticket_id  UUID NOT NULL REFERENCES support_tickets(id) ON DELETE CASCADE,

    -- SET NULL on the admin, never CASCADE -- and this is the load-bearing one. This table is
    -- append-only with no delete endpoint by design; a CASCADE here would quietly delete an
    -- admin's entire note history the day their account is removed, defeating that guarantee
    -- through a path nobody thinks of as deletion. Same reasoning V144 applies to
    -- held_statements.resolved_by: the history outlives the actor. NULL means the author's account
    -- is gone, not that the note was written by the system.
    admin_id   UUID REFERENCES users(id) ON DELETE SET NULL,

    note       TEXT NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    version    BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_support_ticket_notes_by_ticket
    ON support_ticket_internal_notes (ticket_id, created_at);

COMMENT ON TABLE support_ticket_internal_notes IS
    'Admin-only notes on a ticket, append-only. Reachable ONLY from /api/v1/admin/ endpoints -- '
    'never joined into, or serialised by, any user-facing ticket response. Not included in a '
    'user''s data export either: these are Finora''s operational record, not the user''s own data.';

COMMENT ON COLUMN support_ticket_internal_notes.note IS
    'Free text. Deliberately NOT copied into the audit log -- SUPPORT_TICKET_NOTE_ADDED records the '
    'actor and the ticket only. This table is already append-only and admin-scoped, so duplicating '
    'the free text into a second store widens the surface for no gain.';
