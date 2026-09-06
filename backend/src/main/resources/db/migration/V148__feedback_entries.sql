-- Product feedback: "was this helpful", "report an issue", "suggest an improvement".
--
-- Deliberately NOT the same table as support_tickets. Feedback needs no status tracking and no
-- per-row admin action, only aggregation -- giving it a status column it never leaves would invite
-- exactly the triage workflow this scope excludes.

CREATE TABLE feedback_entries (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Nullable, but authentication is REQUIRED to submit in v1, so in practice this is always set.
    -- The column is nullable so that opening feedback to logged-out users later is a controller
    -- change and nothing else -- no migration, no backfill. Stated here so the nullability reads as
    -- a reserved option rather than an oversight.
    --
    -- The CASCADE is a BACKSTOP only, for the reason V145 spells out at length: purgeOne anonymizes
    -- the users row rather than deleting it, so this never fires on the account-deletion path.
    -- Phase 6 MUST add an explicit feedbackEntryRepository.deleteByUserId(userId) call to purgeOne,
    -- or a deleted user's feedback survives the purge.
    user_id     UUID REFERENCES users(id) ON DELETE CASCADE,

    type        VARCHAR(32) NOT NULL,
    context     VARCHAR(64) NOT NULL,
    source      VARCHAR(32) NOT NULL,

    message     TEXT NOT NULL,
    app_version VARCHAR(32),

    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMPTZ,
    version     BIGINT NOT NULL DEFAULT 0
);

-- The admin list is filtered by type/context/source and read newest first.
CREATE INDEX idx_feedback_entries_recent ON feedback_entries (created_at DESC);

COMMENT ON TABLE feedback_entries IS
    'Product feedback, aggregated rather than worked. No status, no assignment, no admin action per '
    'row -- the admin surface is a list with counts by type, context and source.';

COMMENT ON COLUMN feedback_entries.type IS
    'BUG, FEATURE_REQUEST, IMPROVEMENT, GENERAL.';

COMMENT ON COLUMN feedback_entries.context IS
    'Which feature the feedback came from (dashboard, transactions, reports, import-flow, ...). '
    'A bounded Java enum with NO database CHECK constraint, and that omission is deliberate: this '
    'is the one column that gains a value every time a feature ships, and CHECK-constrained enum '
    'columns have already proven expensive here -- V95 added one on sign_in_method and V96 exists '
    'for no other purpose than dropping and recreating it to admit ''APPLE''. Validation lives at '
    'the API boundary via @Enumerated(EnumType.STRING), so adding a value stays a one-constant '
    'change. A free-text key was considered and rejected: it trades the migration for typo drift, '
    'which destroys the aggregation this column exists for.';

COMMENT ON COLUMN feedback_entries.source IS
    'WEB, MOBILE_ANDROID, MOBILE_IOS -- which client, separate from context, which is which feature. '
    'Answers "are mobile users hitting more issues than web" without cross-referencing user-agent '
    'strings after the fact.';
