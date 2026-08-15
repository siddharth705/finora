-- Widens V87's users_status_check to add the second, irreversible phase of the self-service
-- account lifecycle. See UserAccountLifecycleService.requestDeletion / AccountPurgeSweepService
-- for the state machine: ACTIVE -> PENDING_DELETION (self-service, password+OTP gated) ->
-- DELETED (system-driven, 48h later, anonymized in place -- see User.java's own doc comment on
-- why the row is never actually deleted).
ALTER TABLE users DROP CONSTRAINT users_status_check;
ALTER TABLE users ADD CONSTRAINT users_status_check
    CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DEACTIVATED', 'PENDING_DELETION', 'DELETED'));

ALTER TABLE users ADD COLUMN deletion_requested_at TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN deleted_at TIMESTAMPTZ;
