-- D-26 landed while V95's CHECK constraint (sign_in_method IN ('PASSWORD', 'GOOGLE')) was already
-- on main: AuthService.createOAuthUserRecord (the merge of createGoogleUserRecord with the new
-- Apple sign-in path) sets 'APPLE' for an Apple-created account, same random-unguessable-password
-- shape V95's own comment describes for Google. Without widening the constraint, the very first
-- Apple sign-in would fail account creation outright with a CHECK-constraint violation -- not a
-- degraded experience, a hard crash on the one action D-26 exists to enable.
--
-- No backfill needed, unlike V95's own: this repo has shipped no Apple sign-in code before this
-- migration, so there is no existing row anywhere that could already be an Apple account under a
-- wrong label.
ALTER TABLE users DROP CONSTRAINT chk_users_sign_in_method;
ALTER TABLE users ADD CONSTRAINT chk_users_sign_in_method CHECK (sign_in_method IN ('PASSWORD', 'GOOGLE', 'APPLE'));
