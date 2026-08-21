-- Premium Import Reliability v1, §1, item 1.4. statement_imports.status has been declared, defaulted
-- to 'COMPLETED', and never assigned anything else since the column was added (V10) -- a row in this
-- table represents a confirmed, completed import by construction; no code path ever creates one in
-- any other state, and none ever will (FAILED/CANCELLED/in-flight imports never reach this table at
-- all -- see import_jobs and import_sessions for those). A column that can only ever hold one constant
-- value carries no information, and was displayed to users as if it might (StatementHistory.tsx),
-- which is actively misleading. Removed rather than wired to the new user-facing status mapping
-- (UserFacingImportStatus) for the same reason -- wiring it would just be a second place to compute
-- "COMPLETED", always.

ALTER TABLE statement_imports DROP COLUMN status;
