-- Account suspension, backing the new admin portal (frontend-admin/). Previously the only way
-- to stop a compromised/abusive account from logging in was deleting it outright -- there was no
-- reversible "freeze this account" state. status defaults to ACTIVE for every existing row, so
-- this migration cannot lock anyone out on its own; only an explicit admin action (AdminUserService
-- .suspend) ever sets SUSPENDED.
ALTER TABLE users ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE users ADD CONSTRAINT users_status_check CHECK (status IN ('ACTIVE', 'SUSPENDED'));

-- Powers the admin Users directory's status filter (AdminUserController.list) without a full
-- table scan as the user base grows.
CREATE INDEX idx_users_status ON users(status);
