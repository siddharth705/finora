ALTER TABLE categories
    ADD COLUMN icon  VARCHAR(30) NOT NULL DEFAULT 'tag',
    ADD COLUMN color VARCHAR(20) NOT NULL DEFAULT 'gray';

-- Case-insensitive uniqueness at the DB level, replacing the case-sensitive UNIQUE(user_id, name)
-- from V1 -- closes the race documented on CategoryRepository.findByUserIdAndNameIgnoreCaseOrderByIdAsc.
ALTER TABLE categories DROP CONSTRAINT categories_user_id_name_key;
CREATE UNIQUE INDEX uq_categories_user_name_ci ON categories (user_id, lower(name));

-- Backfill real tokens for every existing system category row (AuthService.seedDefaultCategories
-- creates one row per name, per user, at registration -- these UPDATEs apply to every user who
-- has ever registered, not just one).
UPDATE categories SET icon = 'arrow-down-circle', color = 'green'  WHERE is_system AND name = 'Salary';
UPDATE categories SET icon = 'home',               color = 'blue'   WHERE is_system AND name = 'Rent';
UPDATE categories SET icon = 'shopping-cart',       color = 'green'  WHERE is_system AND name = 'Groceries';
UPDATE categories SET icon = 'utensils',            color = 'orange' WHERE is_system AND name = 'Dining';
UPDATE categories SET icon = 'car',                 color = 'gray'   WHERE is_system AND name = 'Transport';
UPDATE categories SET icon = 'zap',                 color = 'yellow' WHERE is_system AND name = 'Utilities';
UPDATE categories SET icon = 'shopping-bag',        color = 'purple' WHERE is_system AND name = 'Shopping';
UPDATE categories SET icon = 'heart-pulse',         color = 'red'    WHERE is_system AND name = 'Health';
UPDATE categories SET icon = 'film',                color = 'pink'   WHERE is_system AND name = 'Entertainment';
UPDATE categories SET icon = 'trending-up',         color = 'teal'   WHERE is_system AND name = 'Investments';
UPDATE categories SET icon = 'percent',             color = 'gray'   WHERE is_system AND name = 'Fees/Interest';
UPDATE categories SET icon = 'repeat',              color = 'blue'   WHERE is_system AND name = 'Transfer';
UPDATE categories SET icon = 'users',               color = 'teal'   WHERE is_system AND name = 'Friend Repayment';
UPDATE categories SET icon = 'landmark',            color = 'red'    WHERE is_system AND name = 'Loan EMI';
UPDATE categories SET icon = 'shield',              color = 'blue'   WHERE is_system AND name = 'Insurance';
UPDATE categories SET icon = 'graduation-cap',      color = 'purple' WHERE is_system AND name = 'Education';
UPDATE categories SET icon = 'refresh-cw',          color = 'pink'   WHERE is_system AND name = 'Subscriptions';
UPDATE categories SET icon = 'plane',               color = 'teal'   WHERE is_system AND name = 'Travel';
UPDATE categories SET icon = 'gift',                color = 'pink'   WHERE is_system AND name = 'Gifts & Donations';
UPDATE categories SET icon = 'paw-print',           color = 'orange' WHERE is_system AND name = 'Pets';
UPDATE categories SET icon = 'sofa',                color = 'yellow' WHERE is_system AND name = 'Home & Furnishing';
UPDATE categories SET icon = 'receipt',             color = 'gray'   WHERE is_system AND name = 'Taxes';
UPDATE categories SET icon = 'banknote',            color = 'green'  WHERE is_system AND name = 'Cash Withdrawal';
UPDATE categories SET icon = 'briefcase',           color = 'blue'   WHERE is_system AND name = 'Business Expenses';
UPDATE categories SET icon = 'tag',                 color = 'gray'   WHERE is_system AND name = 'Other';
