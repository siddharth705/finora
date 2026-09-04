-- The bank an operator sees in the queue, captured at hold time.
--
-- Not joined from import_sessions.detected_account_json, which is where the name actually lives:
-- that row is deleted once its TTL elapses (see ImportSessionService.sweepExpiredSessions and the
-- held-session exemption on it -- an application-code fix, not a migration), so a queue column
-- sourced from it would blank out on exactly the oldest holds. Same snapshot rule as
-- parser_version and reliability_status.
--
-- Nullable: the parser cannot always name a bank, and a hold with no bank is still a valid hold.
ALTER TABLE held_statements ADD COLUMN bank_name VARCHAR(120);

COMMENT ON COLUMN held_statements.bank_name IS
    'Detected bank at hold time. Snapshotted because import_sessions, the only other source, is '
    'swept on a TTL while a hold can outlive it.';
