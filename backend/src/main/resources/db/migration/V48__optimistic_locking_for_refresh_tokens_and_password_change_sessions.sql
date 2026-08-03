-- Bug fix: RefreshTokenService.rotate() and PasswordChangeService.complete() both do a
-- check-then-act sequence (read row, verify its state, write a new state) with no locking.
-- Two concurrent requests presenting the SAME still-valid refresh token (a retried fetch, two
-- tabs) can both read revoked_at IS NULL before either commits its write, both pass the
-- reuse-detection check, and both mint a new token pair -- letting a genuine double-use slip
-- past the "revoked token reused = compromise, revoke everything" detection entirely instead of
-- tripping it. The same TOCTOU shape lets two concurrent password-change completions both pass
-- the OTP_VERIFIED status check before either writes COMPLETED, producing two password-changed
-- audit rows and two notification emails for one user action.
--
-- @Version + Spring Data's default optimistic-locking behavior on save() closes this: a losing
-- concurrent write now throws ObjectOptimisticLockingFailureException instead of silently
-- succeeding -- already handled cleanly by GlobalExceptionHandler.handleOptimisticLock() (added
-- for exactly this exception type against Account/Transaction/Budget/Goal), so no controller or
-- service code needs to change, only these two tables gain the column BaseEntity's sibling
-- tables already have.
ALTER TABLE refresh_tokens ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE password_change_sessions ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
