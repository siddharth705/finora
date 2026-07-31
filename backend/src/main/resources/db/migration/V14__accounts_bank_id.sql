-- Every account now carries a stable bank identifier (see com.finora.util.BankRegistry) so the
-- UI can resolve the official bank name/logo/brand color independently of Account.name (which
-- users can freely rename to a nickname like "Salary Account" without losing the bank identity
-- used for the logo badge). Existing accounts default to 'OTHER' -- there's no statement text
-- left to re-detect from at migration time, so this is an honest "unknown" rather than a guess.
ALTER TABLE accounts ADD COLUMN bank_id VARCHAR(32) NOT NULL DEFAULT 'OTHER';
