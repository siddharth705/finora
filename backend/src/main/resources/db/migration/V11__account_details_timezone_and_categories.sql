-- Three unrelated additions bundled into one migration because they landed in the same pass:
--
-- 1. Accounts page now shows an account holder name and a masked account number (with an
--    eye-toggle to reveal it) instead of just name/type/balance — see Account.java,
--    CsvImportService's new "Account Holder" column detection, and the Setup.tsx frontend.
--    Both nullable: most accounts (manually created, or imported from a file with no such
--    column) simply won't have one, same as credit_limit/due_date already work.
ALTER TABLE accounts ADD COLUMN account_holder_name VARCHAR(255);
ALTER TABLE accounts ADD COLUMN account_number_masked VARCHAR(64);

-- 2. The Dashboard's "Good morning/afternoon/evening" greeting used to read the browser's local
--    clock, which is right for the common case but wrong the moment someone's system clock is
--    on a different zone than the one they actually keep finance-app hours in. Users can change
--    this in Settings; Asia/Kolkata is the default because every bundled sample statement and
--    currency format in this app is India-specific.
ALTER TABLE users ADD COLUMN timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Kolkata';

-- 3. AuthService.DEFAULT_CATEGORIES grew from 13 starter categories to include everyday cases
--    the original list missed (repaying a friend, EMIs, insurance, education, subscriptions,
--    travel, gifts, taxes, cash withdrawals, business expenses) — but that seeding only runs at
--    registration time, so it wouldn't reach anyone who signed up before this migration. Backfill
--    the same additions for every existing user, skipping any name a user already has (either
--    because they registered after this shipped in a dev environment, or because they'd already
--    hand-created a category with that exact name — resolveOrCreateCategory dedupes by name per
--    user, and this respects the same rule).
INSERT INTO categories (id, user_id, name, is_system)
SELECT gen_random_uuid(), u.id, new_cat.name, true
FROM users u
CROSS JOIN (VALUES
    ('Friend Repayment'), ('Loan EMI'), ('Insurance'), ('Education'), ('Subscriptions'),
    ('Travel'), ('Gifts & Donations'), ('Pets'), ('Home & Furnishing'), ('Taxes'),
    ('Cash Withdrawal'), ('Business Expenses')
) AS new_cat(name)
WHERE NOT EXISTS (
    SELECT 1 FROM categories c WHERE c.user_id = u.id AND c.name = new_cat.name
);
