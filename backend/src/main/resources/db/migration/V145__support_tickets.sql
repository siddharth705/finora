-- Support tickets: the in-product replacement for a mailto: link.
--
-- Scope is deliberately small and is documented in docs/proposals/support-help-feedback-proposal.md
-- -- a ticket is created by a user, an admin moves its status, done. No SLA timer, no routing, no
-- conversation thread. The one operational affordance is claimed_by_admin_id below.

-- Human-facing reference, minted at creation. Mirrors held_statement_reference_seq (V144): the UUID
-- stays the real key and the foreign key everywhere, and this exists so nobody has to read a UUID
-- aloud. nextval is transactional-but-not-rollback-safe by design -- a rolled-back ticket burns its
-- number rather than reissuing it, because a reused reference would point at two different tickets.
--
-- Format is SUP-000001: no year segment, deliberately diverging from V144's HLD-2026-000001. Six
-- digits is a minimum width, not a maximum, so past a million tickets the reference gets longer
-- instead of wrapping.
CREATE SEQUENCE support_ticket_reference_seq START 1;

CREATE TABLE support_tickets (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_number       VARCHAR(32) NOT NULL UNIQUE,

    -- CASCADE here is a BACKSTOP, not the deletion mechanism, and the difference matters.
    -- AccountPurgeSweepService.purgeOne ANONYMIZES the users row -- it sets a tombstone email,
    -- clears the name and phone, and calls userRepository.save(). It never issues a DELETE FROM
    -- users, so this CASCADE never fires on the account-deletion path. That trap is already
    -- documented in purgeOne itself, against V137/V125: "that alone never fires".
    --
    -- Therefore Phase 6 MUST add an explicit supportTicketRepository.deleteByUserId(userId) call
    -- to purgeOne's ordered bulk-delete block. Without it a deleted user's support tickets --
    -- including their free-text description of a problem with their own money -- survive the
    -- purge indefinitely. The CASCADE is kept anyway, matching V137's own choice, so that any
    -- future hard delete of a user cannot orphan these rows.
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    category            VARCHAR(32) NOT NULL,
    status              VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    source              VARCHAR(32) NOT NULL,

    subject             VARCHAR(120) NOT NULL,
    description         TEXT NOT NULL,
    app_version         VARCHAR(32),

    -- SET NULL, never CASCADE, matching V144's held_statements.resolved_by: deleting an admin
    -- account must not rewrite the record of what they were working on. The history outlives the
    -- actor. Admins are rows in users (V52's account_scope), so this is a live path.
    claimed_by_admin_id UUID REFERENCES users(id) ON DELETE SET NULL,

    resolved_at         TIMESTAMPTZ,
    closed_at           TIMESTAMPTZ,

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at          TIMESTAMPTZ,
    version             BIGINT NOT NULL DEFAULT 0
);

-- The queue's ordering: oldest first, matching AdminHeldImportService.list and
-- AdminLearningQueueService -- the longest-waiting user is the one to look at.
CREATE INDEX idx_support_tickets_open ON support_tickets (created_at)
    WHERE status IN ('OPEN', 'IN_PROGRESS');

-- "My tickets", newest first.
CREATE INDEX idx_support_tickets_by_user ON support_tickets (user_id, created_at DESC);

COMMENT ON TABLE support_tickets IS
    'In-product support requests. deleted_at exists because these entities extend BaseEntity and '
    'is deliberately unused in v1: there is no delete endpoint for a ticket, user-facing or admin. '
    'A column being present is not the same as deletion being supported.';

COMMENT ON COLUMN support_tickets.category IS
    'STATEMENT_IMPORT, CATEGORIZATION, ACCOUNT_LINKING, DATA_ACCURACY, TECHNICAL_ISSUE, OTHER. '
    'Enum-backed VARCHAR with no CHECK constraint, the majority pattern in this schema -- V95 added '
    'a CHECK on sign_in_method and V96 exists solely to drop and recreate it for one more value.';

COMMENT ON COLUMN support_tickets.status IS
    'OPEN, IN_PROGRESS, RESOLVED, CLOSED. Transitions are enforced in SupportTicketService, not '
    'here: OPEN moves to any of the other three; IN_PROGRESS to RESOLVED or CLOSED; RESOLVED only '
    'to CLOSED; CLOSED is terminal. Anything else is a 409. RESOLVED deliberately cannot return to '
    'IN_PROGRESS -- a customer whose issue is not actually fixed raises a new request.';

COMMENT ON COLUMN support_tickets.source IS
    'WEB, MOBILE_ANDROID, MOBILE_IOS -- which client submitted it, captured from a request header '
    'rather than inferred from a user-agent string. Distinct from FeedbackEntry.context, which is '
    'which feature the submission came from.';

COMMENT ON COLUMN support_tickets.claimed_by_admin_id IS
    'Which admin is currently working this, so two do not answer the same ticket. Deliberately NOT '
    'assignment: an admin claims a ticket themselves, nobody routes one to anybody. A claim can be '
    'taken over by any other admin -- it warns, it never blocks -- so one person going on leave '
    'cannot freeze a customer''s ticket. The handoff is recoverable only from the audit trail.';
