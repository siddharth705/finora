-- Financial Intelligence Workspace, System Settings module. Deliberately a NEW, separate table
-- from `users` (which already carries personal UI preferences: low_balance_threshold, theme,
-- timezone -- see UserSettingsService). auto_apply_confidence_threshold is a Workspace/system
-- concept, not a UI preference, and keeping it here means this table can grow into the home for
-- future Workspace-level settings without further overloading the users table.
--
-- Scope (confirmed): exactly one real, persisted, editable setting for now.
-- auto_apply_confidence_threshold is stored 0-100 (same scale as ConfidenceEngine's existing int
-- confidence values, e.g. DEFAULT_AUTO_APPLY_THRESHOLD = 90) rather than a 0.0-1.0 decimal, so it
-- can slot into ConfidenceEngine.meetsAutoApplyThreshold(int, int) without a unit conversion if/
-- when that dead-code hook gets wired into a live decision path later. It is NOT wired into any
-- behavior yet -- see UserSettingsWorkspaceService's class comment for why that's out of scope
-- for this change. Every other Workspace setting the System Settings page shows is a static,
-- unpersisted "coming in a future release" label on the frontend -- there was nothing to persist
-- for those yet.
CREATE TABLE user_settings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    auto_apply_confidence_threshold INT NOT NULL DEFAULT 90,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
