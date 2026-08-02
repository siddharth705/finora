-- Nullable, backfilled to nothing: existing accounts have no recorded password-change event, and
-- showing a guessed date (e.g. falling back to created_at) would misrepresent a fact the app
-- doesn't actually have. Settings' "Last changed" only renders once this is genuinely set -- see
-- AuthService.changePassword()/resetPassword(), both of which set it (a password reset via the
-- forgot-password flow is still a password change for this purpose).
ALTER TABLE users ADD COLUMN password_changed_at TIMESTAMPTZ;
