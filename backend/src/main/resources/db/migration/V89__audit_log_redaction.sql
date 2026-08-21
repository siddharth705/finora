-- BH-044. audit_logs has no retention policy today -- see AuditService's class doc for the full
-- history of that gap. Sid's decision, recorded 2026-08-15: the audit EVENT itself (actor, action,
-- entity, timestamp, correlation id) is kept forever; the metadata JSONB payload -- which carries
-- real financial content, e.g. TRANSACTION_DELETED's amount/description, BUDGET_UPSERTED's limit
-- -- is redacted entirely, replaced with a small marker object, once a row is older than the
-- configured retention window (see application.yml's app.audit.redaction block for the exact
-- window and rationale). This is in-place redaction on this table, not a split into a separate
-- payload table. The sweep itself lives in AuditService, beside record() -- see that class's own
-- doc comment on why it must not be a bare repository deleteBy... method.

-- NULL until AuditService's redaction sweep processes this row; set to the moment it did
-- afterward. Doubles as both the "already redacted, do not reprocess" marker and an audit trail of
-- when redaction happened -- itself potentially useful evidence if a compliance question ever asks
-- whether this row's metadata was still present as of some date. Nullable, no default: on
-- Postgres 11+ this is a fast metadata-only change, no table rewrite.
ALTER TABLE audit_logs ADD COLUMN redacted_at TIMESTAMPTZ;

-- Backs the sweep's age-based candidate scan. Partial on "not yet redacted" rather than a plain
-- index on created_at: as more rows get redacted over time the set of rows this index has to
-- cover shrinks, not grows, so it stays small indefinitely instead of growing with the table
-- forever. No index on created_at alone existed before this -- idx_audit_user is
-- (user_id, created_at DESC), which is no help to a scan with no user_id predicate.
--
-- Deliberately NOT CONCURRENTLY, even though a plain CREATE INDEX takes a SHARE lock for its full
-- build duration (which blocks writes -- AuditService.record() is called synchronously from ~70
-- call sites across nearly every mutating request in the app). CONCURRENTLY was tried first and
-- reverted after reproducing a real deadlock in testing: it must wait for every transaction that
-- was already open at its start to finish, and Flyway/HikariCP's own connection-pool behavior at
-- migration time left one such connection sitting idle-in-transaction indefinitely, hanging this
-- migration -- and therefore application startup -- for as long as that connection stayed open,
-- with no natural resolution. An indefinite startup hang is a strictly worse failure mode than a
-- brief write lock during a migration window, so the plain, transactional form is the safer
-- choice here -- same precedent V71__live_session_lookup_index.sql already established for a
-- comparable (if smaller) index in this codebase.
CREATE INDEX idx_audit_logs_created_at_unredacted ON audit_logs(created_at) WHERE redacted_at IS NULL;
