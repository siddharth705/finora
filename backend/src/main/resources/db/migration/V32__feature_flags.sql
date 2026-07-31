-- Admin Portal Phase 8 (Feature Flags) -- a real on/off switch table, not UI theater. One flag is
-- seeded and actually wired into RecurringService.detectForUser as proof: RECURRING_DETECTION_ENABLED,
-- defaulted to true so flipping this migration in does not change any existing behavior. See
-- RecurringService's doc comment for how the gate is applied.

CREATE TABLE feature_flags (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    key         VARCHAR(100) NOT NULL UNIQUE,
    description VARCHAR(500) NOT NULL,
    enabled     BOOLEAN      NOT NULL DEFAULT true,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO feature_flags (key, description, enabled) VALUES
    ('RECURRING_DETECTION_ENABLED',
     'Gates RecurringService.detectForUser -- the subscription/EMI recurring-pattern detection pass that runs after every statement import and transaction create/edit/delete. Defaults on (matches current always-on behavior); can be switched off platform-wide without a deploy if the detection pass needs to be paused, e.g. investigating a bad rule causing false-positive recurring badges.',
     true);
