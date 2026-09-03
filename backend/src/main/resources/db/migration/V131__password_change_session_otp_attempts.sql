-- Task 13: an attempt cap on OTP verification within a single password-change session, mirroring
-- AuthService's per-account login lockout (failed_login_attempts / locked_until on users). Today
-- verifyOtp() is bounded only by RateLimitFilter's shared per-IP limit and the session's own
-- 15-minute expiry -- nothing stops repeated wrong/forged Firebase tokens against ONE session
-- within that window.
--
-- Defaulted to 0, not left nullable: password_change_sessions may already have live rows (any
-- in-flight STARTED/OTP_VERIFIED session at deploy time), and PasswordChangeService.verifyOtp()
-- compares this column with >= on every call -- a NULL would make that comparison unknown rather
-- than false, so an in-flight session would need NULL-safe handling in application code for no
-- benefit. Every existing row's real attempt count to date is 0 in the only sense that matters
-- here: verifyOtp() never persisted a per-attempt counter before this column existed, so there is
-- no historical count to backfill -- 0 is not a guess, it is the accurate count of column-tracked
-- attempts so far.
ALTER TABLE password_change_sessions ADD COLUMN otp_attempt_count INTEGER NOT NULL DEFAULT 0;

COMMENT ON COLUMN password_change_sessions.otp_attempt_count IS
    'Wrong/invalid OTP verification attempts against this session (an invalid or expired Firebase '
    'token, or one that proves a different phone number than the account''s own). Reset to 0 on a '
    'successful verifyOtp(); at PasswordChangeService.MAX_OTP_ATTEMPTS, the session is rejected on '
    'every subsequent verify -- including one presenting a token that would otherwise pass -- '
    'without ever calling PhoneVerificationProvider again. Mirrors users.failed_login_attempts '
    '(V1) except scoped to one session rather than persisted lockout across the account, since the '
    'session''s own expires_at already bounds how long an exhausted session can be retried at all.';
