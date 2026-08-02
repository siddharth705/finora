-- Bug fix: password_history (V44) started recording a row on every password WRITE going forward,
-- but never backfilled anything for accounts that already existed and haven't changed their
-- password since -- those rows had zero history, silently defeating the "reject a password
-- identical to one of your last 5" check for exactly the users who've had the same password the
-- longest. Seeds one row per such user from their current hash, so the check has something to
-- compare the very next time they set a new password.
INSERT INTO password_history (id, user_id, password_hash, created_at)
SELECT gen_random_uuid(), u.id, u.password_hash, COALESCE(u.password_changed_at, u.created_at)
FROM users u
WHERE NOT EXISTS (SELECT 1 FROM password_history ph WHERE ph.user_id = u.id);
