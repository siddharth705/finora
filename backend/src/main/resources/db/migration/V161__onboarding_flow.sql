-- V161: First-login onboarding flow (tour + Financial Focus + getting-started checklist).
-- See docs/superpowers/specs/2026-09-06-first-login-onboarding-tour-design.md.

-- Same "nullable _at, set once, never cleared except by an explicit reset" convention as
-- users.deactivated_at (V88) and users.password_changed_at (V40). NULL = onboarding not yet
-- completed. Backfilled below in the same migration, same reasoning as V99's subscriptions
-- backfill: without it, every existing user would be ambushed by a tour on their next login.
ALTER TABLE users ADD COLUMN onboarding_completed_at TIMESTAMPTZ;
UPDATE users SET onboarding_completed_at = now() WHERE onboarding_completed_at IS NULL;

-- Multi-select answer to the Financial Focus onboarding question. Shaped like
-- feature_entitlements (V99): a child table for "a small set of tagged values per user", not
-- @ElementCollection (no entity in this codebase uses it) and not a CSV/JSON column.
CREATE TABLE user_financial_focus (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    focus_key VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, focus_key)
);
CREATE INDEX idx_user_financial_focus_user_id ON user_financial_focus(user_id);

-- The 2 getting-started checklist items with no natural signal elsewhere in the schema
-- ("did the user open this screen") -- see the design spec §4 for why the other 4 items are
-- derived live from ImportJob/Budget/Goal/User instead of stored here. Deliberately a closed,
-- 2-value set (REVIEW_TRANSACTIONS, VIEW_INSIGHTS), enforced in the service layer, not a general
-- analytics-events table.
CREATE TABLE user_checklist_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    item_key VARCHAR(30) NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, item_key)
);
CREATE INDEX idx_user_checklist_events_user_id ON user_checklist_events(user_id);
