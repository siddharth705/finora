-- Optional product-feedback capture for self-service deactivation (UserAccountLifecycleService
-- .deactivate() -- see its own doc comment). Deliberately NOT cleared on reactivation: the whole
-- point is churn analysis, which needs the last reason a user gave even after they come back, the
-- same way password_changed_at (V40) persists indefinitely as "last time this happened" rather
-- than being reset by unrelated activity.
ALTER TABLE users ADD COLUMN deactivation_reason VARCHAR(50);
ALTER TABLE users ADD COLUMN deactivation_note VARCHAR(500);
ALTER TABLE users ADD COLUMN deactivated_at TIMESTAMPTZ;

-- Mirrors the shape of the users_status_check CHECK constraint (V23/V84): the column is not free
-- text, so the allowed set is enforced at the database too, not just in
-- UserAccountLifecycleService's validation. NULL is allowed (accounts deactivated before this
-- migration, and any future path that sets DEACTIVATED without asking for a reason) but an
-- unrecognized non-null value is not.
ALTER TABLE users ADD CONSTRAINT users_deactivation_reason_check
    CHECK (deactivation_reason IS NULL OR deactivation_reason IN
        ('TAKING_A_BREAK', 'NOT_USING_ANYMORE', 'PRIVACY_CONCERNS', 'USING_ANOTHER_APP', 'OTHER'));
