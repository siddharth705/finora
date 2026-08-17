-- AuthService.createGoogleUserRecord leaves a Google Sign-In account with a random, unguessable
-- passwordHash (nobody knows it, including the user) -- there was previously no durable way to
-- tell such an account apart from an ordinary one, which is exactly what let three separate
-- "enter your current password" gates (PasswordChangeService.start, UserAccountLifecycleService
-- .deactivate, DataExportService.buildBundle) silently lock every Google-only user out of
-- changing their password, deleting their account, and exporting their data -- each check could
-- never pass, since there is no real password to enter.
--
-- Review catch: the first version of this migration defaulted every existing row to PASSWORD on
-- the reasoning that Google Sign-In (#160) "shipped very recently." That reasoning was never
-- actually checked against the real timeline -- #160 is already merged, so any real user who
-- signed up via Google before THIS migration runs would have been silently mistagged PASSWORD,
-- reproducing on this exact cohort the identical lockout this migration exists to fix (now via a
-- passwordEncoder.matches() call against a hash they still can't know, instead of the missing
-- column). Fixed below with a real backfill instead of trusting the column default alone.
--
-- The backfill signal: phone_number IS NULL. Every other account-creation path (registration,
-- admin-create) requires a real phone number (see AuthService.createUserRecord); Google Sign-In
-- is the only writer of passwordHash that leaves it null, and -- as of this migration -- there is
-- no shipped self-service way for a Google account to add one afterward, so every existing
-- null-phone row was created by createGoogleUserRecord, which also hardcodes SCOPE_USER.
ALTER TABLE users ADD COLUMN sign_in_method VARCHAR(20) NOT NULL DEFAULT 'PASSWORD';
ALTER TABLE users ADD CONSTRAINT chk_users_sign_in_method CHECK (sign_in_method IN ('PASSWORD', 'GOOGLE'));
UPDATE users SET sign_in_method = 'GOOGLE' WHERE phone_number IS NULL AND account_scope = 'USER';
