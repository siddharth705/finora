-- Admin-managed banks, additive to (never replacing) the built-in ~40-bank BankRegistry
-- (com.finora.util.BankRegistry). BankRegistry stays exactly as-is -- it's verified reference
-- data (names, colors, IFSC prefixes) that many existing code paths depend on (CSV bank
-- auto-detection, the account bank picker, transaction search) and rewriting it into this table
-- would be a much larger, riskier migration for no real benefit. This table exists purely so an
-- admin can add a bank BankRegistry doesn't cover (a smaller regional bank, say) without a code
-- deploy -- see BankManagementService, which merges both sources at read time.
--
-- id is the bank's own short code (e.g. "IOB"), not a generated UUID -- it plays the exact same
-- role BankRegistry.BankInfo.id() does (stored directly in accounts.bank_id), and app code needs
-- to look banks up by that code, not by a separate surrogate key.
CREATE TABLE banks (
    id                      VARCHAR(30) PRIMARY KEY,
    official_name           VARCHAR(150) NOT NULL,
    short_name              VARCHAR(100) NOT NULL,
    color_hex               VARCHAR(7)  NOT NULL DEFAULT '#64748B',
    initials                VARCHAR(6)  NOT NULL DEFAULT '',
    category                VARCHAR(20),
    website_url             VARCHAR(255),
    ifsc_prefix             VARCHAR(4),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
