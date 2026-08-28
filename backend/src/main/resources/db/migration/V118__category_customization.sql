ALTER TABLE categories
    ADD COLUMN icon  VARCHAR(30) NOT NULL DEFAULT 'tag',
    ADD COLUMN color VARCHAR(20) NOT NULL DEFAULT 'gray';

-- ---------------------------------------------------------------------------------------------
-- De-duplication, BEFORE the case-insensitive unique index below can possibly be created.
--
-- This is not defensive tidying: V1's UNIQUE(user_id, name) is CASE-SENSITIVE, so "Fuel" and
-- "fuel" for the same user are two perfectly legal rows today, and
-- CategoryRepository.findByUserIdAndNameIgnoreCaseOrderByIdAsc's own doc comment records that
-- exact state as a known, expected one ("a user who already has both 'Dining' and 'dining' from
-- BEFORE this fix shipped") -- it returns a List rather than an Optional purely to survive it.
-- A single such pair anywhere makes CREATE UNIQUE INDEX below fail, aborting this whole migration
-- and leaving the backend unable to boot until someone repairs the data by hand.
--
-- Survivor = the LOWEST id in each (user_id, lower(name)) group. That is not an arbitrary pick:
-- it is exactly the row CategorizationService.resolveOrCreateCategory has been resolving these
-- duplicates to all along (matches.get(0) off ...OrderByIdAsc), so the row kept here is the row
-- the running system already treats as the real one.
--
-- Every FK into categories(id) is repointed at the survivor BEFORE the losers are deleted, so
-- nothing is lost to the CASCADEs. All seven columns, from a full grep of "REFERENCES categories":
--   merchant_category_map.category_id            (V1,  CASCADE)   -- repointed; its UNIQUE is on
--                                                                    (user_id, normalized_desc),
--                                                                    which category_id is not
--                                                                    part of, so it cannot collide
--   transactions.category_id                     (V1,  SET NULL)  -- repointed
--   budgets.category_id                          (V1,  CASCADE)   -- repointed, see below
--   merchant_category_learning.category_id       (V7,  CASCADE)   -- merged, see below
--   merchant_learning_audit.previous_category_id (V7,  NO ACTION) -- repointed (nullable, no
--   merchant_learning_audit.new_category_id      (V7,  NO ACTION)    constraint to collide with;
--                                                                    unlike CategoryService.delete
--                                                                    these are NOT cleared to NULL
--                                                                    -- the survivor is the same
--                                                                    category the user chose, just
--                                                                    spelled differently, so the
--                                                                    audit trail stays truthful)
--   merchant_learning_events.category_id         (V62, CASCADE)   -- repointed. V62's comment
--                                                                    cascades events whose category
--                                                                    "no longer exists"; here it
--                                                                    still does, under one id, so
--                                                                    the event stays retryable
-- category_rules.action_value is a category NAME, not an id -- deliberately untouched. The
-- surviving row's name differs from a loser's only in case, and every lookup of it
-- (CategoryRuleRepository.findByUserIdAndActionTypeInAndActionValueIgnoreCase) is already
-- case-insensitive, so both spellings keep resolving to the survivor.
--
-- The loser->survivor mapping below is derived purely from `categories`. Only the survivor's own
-- name/is_system are rewritten before the final DELETE, and only within its own group, so
-- lower(name) -- the thing the mapping partitions on -- never changes: each statement recomputing
-- the mapping independently gets the same answer. Once this has run there are no groups left with
-- more than one row, which makes every statement a no-op on a second run.

-- V1's case-sensitive constraint is dropped up front rather than after the de-duplication,
-- because the de-duplication itself has to be able to violate it: promoting a system row's
-- canonical spelling onto its survivor (immediately below) means two rows briefly hold the same
-- exact name, until the loser is deleted at the end of this block.
-- IF EXISTS: deterministic that V1 created this constraint, but application.yml sets
-- baseline-on-migrate: true, so a schema adopted at a baseline rather than migrated from V1
-- forward could be missing it -- and a failure here blocks the backend's boot.
ALTER TABLE categories DROP CONSTRAINT IF EXISTS categories_user_id_name_key;

-- A group can legitimately mix a system row and a user-created one ("Dining" seeded at
-- registration + "dining" typed later by an import). Rather than change the survivor rule for
-- that case, system-ness and the system row's canonical name are promoted ONTO the survivor:
-- the user keeps an immutable, correctly-spelled system category, and the icon/color backfill
-- further down (which matches on `is_system AND name = '...'`) still finds it.
WITH dup AS (
    SELECT id,
           first_value(id) OVER (PARTITION BY user_id, lower(name) ORDER BY id) AS survivor_id,
           count(*)        OVER (PARTITION BY user_id, lower(name))            AS group_size
    FROM categories
),
grp AS (SELECT id, survivor_id FROM dup WHERE group_size > 1),
system_rows AS (
    SELECT g.survivor_id, min(c.name) AS system_name
    FROM grp g JOIN categories c ON c.id = g.id
    WHERE c.is_system
    GROUP BY g.survivor_id
)
UPDATE categories c
SET is_system = true, name = s.system_name
FROM system_rows s
WHERE c.id = s.survivor_id;

WITH dup AS (
    SELECT id,
           first_value(id) OVER (PARTITION BY user_id, lower(name) ORDER BY id) AS survivor_id,
           count(*)        OVER (PARTITION BY user_id, lower(name))            AS group_size
    FROM categories
),
grp AS (SELECT id, survivor_id FROM dup WHERE group_size > 1 AND id <> survivor_id)
UPDATE merchant_category_map m SET category_id = g.survivor_id
FROM grp g WHERE m.category_id = g.id;

WITH dup AS (
    SELECT id,
           first_value(id) OVER (PARTITION BY user_id, lower(name) ORDER BY id) AS survivor_id,
           count(*)        OVER (PARTITION BY user_id, lower(name))            AS group_size
    FROM categories
),
grp AS (SELECT id, survivor_id FROM dup WHERE group_size > 1 AND id <> survivor_id)
UPDATE transactions t SET category_id = g.survivor_id
FROM grp g WHERE t.category_id = g.id;

WITH dup AS (
    SELECT id,
           first_value(id) OVER (PARTITION BY user_id, lower(name) ORDER BY id) AS survivor_id,
           count(*)        OVER (PARTITION BY user_id, lower(name))            AS group_size
    FROM categories
),
grp AS (SELECT id, survivor_id FROM dup WHERE group_size > 1 AND id <> survivor_id)
UPDATE merchant_learning_audit a SET previous_category_id = g.survivor_id
FROM grp g WHERE a.previous_category_id = g.id;

WITH dup AS (
    SELECT id,
           first_value(id) OVER (PARTITION BY user_id, lower(name) ORDER BY id) AS survivor_id,
           count(*)        OVER (PARTITION BY user_id, lower(name))            AS group_size
    FROM categories
),
grp AS (SELECT id, survivor_id FROM dup WHERE group_size > 1 AND id <> survivor_id)
UPDATE merchant_learning_audit a SET new_category_id = g.survivor_id
FROM grp g WHERE a.new_category_id = g.id;

WITH dup AS (
    SELECT id,
           first_value(id) OVER (PARTITION BY user_id, lower(name) ORDER BY id) AS survivor_id,
           count(*)        OVER (PARTITION BY user_id, lower(name))            AS group_size
    FROM categories
),
grp AS (SELECT id, survivor_id FROM dup WHERE group_size > 1 AND id <> survivor_id)
UPDATE merchant_learning_events e SET category_id = g.survivor_id
FROM grp g WHERE e.category_id = g.id;

-- budgets: UNIQUE(user_id, category_id). Repointing blind would collide whenever more than one
-- row of a group carries a budget. Exactly one budget per group is kept -- the survivor's own if
-- it has one (the user set that limit against the category the app was already resolving to),
-- otherwise the one on the lowest-id loser, which is then repointed by the statement after
-- this one (ordering by category_id, not by the budget row's own random id, so which limit
-- survives is deterministic rather than an accident of insertion). The
-- others are DELETED rather than merged: two monthly limits for one category have no defensible
-- combination (summing them invents a limit the user never chose).
-- Audit artifact: the monthly limits the DELETE below drops are otherwise gone without trace, and
-- there is no way to reconstruct them afterwards. Captured from the pre-DELETE state, with the
-- category id each budget was originally attached to. Empty in the common case (no duplicates, or
-- at most one budget per group) and intentionally left in place forever -- nothing drops it.
CREATE TABLE v118_dropped_budgets AS
WITH dup AS (
    SELECT id, user_id,
           first_value(id) OVER (PARTITION BY user_id, lower(name) ORDER BY id) AS survivor_id,
           count(*)        OVER (PARTITION BY user_id, lower(name))            AS group_size
    FROM categories
),
grp AS (SELECT id, user_id, survivor_id FROM dup WHERE group_size > 1),
ranked AS (
    SELECT b.id AS budget_id, g.survivor_id,
           row_number() OVER (PARTITION BY b.user_id, g.survivor_id
                              ORDER BY (b.category_id = g.survivor_id) DESC, b.category_id, b.id) AS rn
    FROM budgets b JOIN grp g ON b.category_id = g.id
)
SELECT b.id AS budget_id,
       b.user_id,
       b.category_id AS original_category_id,
       r.survivor_id AS surviving_category_id,
       b.monthly_limit,
       b.deleted_at,
       b.created_at,
       b.updated_at,
       now() AS dropped_at
FROM ranked r JOIN budgets b ON b.id = r.budget_id
WHERE r.rn > 1;

-- CREATE TABLE ... AS SELECT copies no constraints from the source -- this table must stay
-- self-purging the same way every other per-user table in this app is: AccountPurgeSweepService
-- walks a fixed list of tables to hard-delete on account deletion and has no knowledge of this one,
-- so without this FK a deleted account's user_id and the monthly limit it once set would survive
-- account erasure indefinitely in an unowned table.
ALTER TABLE v118_dropped_budgets
    ADD CONSTRAINT v118_dropped_budgets_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

WITH dup AS (
    SELECT id, user_id,
           first_value(id) OVER (PARTITION BY user_id, lower(name) ORDER BY id) AS survivor_id,
           count(*)        OVER (PARTITION BY user_id, lower(name))            AS group_size
    FROM categories
),
grp AS (SELECT id, user_id, survivor_id FROM dup WHERE group_size > 1),
ranked AS (
    SELECT b.id AS budget_id,
           row_number() OVER (PARTITION BY b.user_id, g.survivor_id
                              ORDER BY (b.category_id = g.survivor_id) DESC, b.category_id, b.id) AS rn
    FROM budgets b JOIN grp g ON b.category_id = g.id
)
DELETE FROM budgets WHERE id IN (SELECT budget_id FROM ranked WHERE rn > 1);

WITH dup AS (
    SELECT id,
           first_value(id) OVER (PARTITION BY user_id, lower(name) ORDER BY id) AS survivor_id,
           count(*)        OVER (PARTITION BY user_id, lower(name))            AS group_size
    FROM categories
),
grp AS (SELECT id, survivor_id FROM dup WHERE group_size > 1 AND id <> survivor_id)
UPDATE budgets b SET category_id = g.survivor_id, updated_at = now()
FROM grp g WHERE b.category_id = g.id;

-- merchant_category_learning: UNIQUE(user_id, merchant_id, category_id), same collision shape as
-- budgets but with a defensible merge, so it gets one -- identical semantics to
-- MerchantLearningService.repointCategory, which solves this exact problem at runtime: the
-- confirmation counts are SUMMED (they are all real confirmations of the same category, just
-- reached under two spellings; dropping them would under-weight it in
-- ConfidenceEngine.topCategory) and the later last_confirmed_at wins. One keeper row per
-- (user, merchant, group) absorbs the totals and takes the survivor's id; the rest are deleted.
WITH dup AS (
    SELECT id,
           first_value(id) OVER (PARTITION BY user_id, lower(name) ORDER BY id) AS survivor_id,
           count(*)        OVER (PARTITION BY user_id, lower(name))            AS group_size
    FROM categories
),
grp AS (SELECT id, survivor_id FROM dup WHERE group_size > 1),
ranked AS (
    SELECT l.id AS learning_id, l.user_id, l.merchant_id, g.survivor_id,
           row_number() OVER (PARTITION BY l.user_id, l.merchant_id, g.survivor_id
                              ORDER BY (l.category_id = g.survivor_id) DESC, l.category_id, l.id) AS rn
    FROM merchant_category_learning l JOIN grp g ON l.category_id = g.id
),
totals AS (
    SELECT r.user_id, r.merchant_id, r.survivor_id,
           sum(l.confirmation_count) AS total_count,
           max(l.last_confirmed_at)  AS latest_confirmed
    FROM ranked r JOIN merchant_category_learning l ON l.id = r.learning_id
    GROUP BY r.user_id, r.merchant_id, r.survivor_id
)
UPDATE merchant_category_learning l
SET category_id        = t.survivor_id,
    confirmation_count = t.total_count,
    last_confirmed_at  = t.latest_confirmed,
    updated_at         = now()
FROM ranked r
JOIN totals t ON t.user_id = r.user_id AND t.merchant_id = r.merchant_id
             AND t.survivor_id = r.survivor_id
WHERE l.id = r.learning_id AND r.rn = 1;

-- Safe to recompute `ranked` after the UPDATE above: a keeper that was a loser row now carries
-- category_id = survivor_id, which still sorts it first in its own partition, so rn = 1 picks the
-- same rows again and rn > 1 is still exactly the absorbed ones.
WITH dup AS (
    SELECT id,
           first_value(id) OVER (PARTITION BY user_id, lower(name) ORDER BY id) AS survivor_id,
           count(*)        OVER (PARTITION BY user_id, lower(name))            AS group_size
    FROM categories
),
grp AS (SELECT id, survivor_id FROM dup WHERE group_size > 1),
ranked AS (
    SELECT l.id AS learning_id,
           row_number() OVER (PARTITION BY l.user_id, l.merchant_id, g.survivor_id
                              ORDER BY (l.category_id = g.survivor_id) DESC, l.category_id, l.id) AS rn
    FROM merchant_category_learning l JOIN grp g ON l.category_id = g.id
)
DELETE FROM merchant_category_learning WHERE id IN (SELECT learning_id FROM ranked WHERE rn > 1);

-- Merging shifts every OTHER category's share of the affected merchant's total, so confidence is
-- recomputed across each touched merchant's whole distribution -- the same thing
-- MerchantLearningService.repointCategory does via recomputeAndSave, and the same formula as
-- ConfidenceEngine.recomputeDistribution (share of total, rounded; 0 when the merchant has no
-- confirmations at all). Merchants that merely had a survivor row and no merge are swept up too;
-- for them this recomputes the value they already had.
WITH dup AS (
    SELECT id,
           first_value(id) OVER (PARTITION BY user_id, lower(name) ORDER BY id) AS survivor_id,
           count(*)        OVER (PARTITION BY user_id, lower(name))            AS group_size
    FROM categories
),
survivors AS (SELECT DISTINCT survivor_id FROM dup WHERE group_size > 1),
affected AS (
    SELECT DISTINCT l.user_id, l.merchant_id
    FROM merchant_category_learning l
    WHERE l.category_id IN (SELECT survivor_id FROM survivors)
),
totals AS (
    SELECT l.user_id, l.merchant_id, sum(l.confirmation_count) AS total
    FROM merchant_category_learning l
    JOIN affected a ON a.user_id = l.user_id AND a.merchant_id = l.merchant_id
    GROUP BY l.user_id, l.merchant_id
)
UPDATE merchant_category_learning l
SET confidence = CASE WHEN t.total > 0
                      THEN round(l.confirmation_count * 100.0 / t.total)
                      ELSE 0 END,
    updated_at = now()
FROM totals t
WHERE l.user_id = t.user_id AND l.merchant_id = t.merchant_id;

-- Every reference now points at a survivor, so this deletes nothing but the redundant rows
-- themselves (the remaining CASCADEs have nothing left to take with them).
WITH dup AS (
    SELECT id,
           first_value(id) OVER (PARTITION BY user_id, lower(name) ORDER BY id) AS survivor_id,
           count(*)        OVER (PARTITION BY user_id, lower(name))            AS group_size
    FROM categories
)
DELETE FROM categories WHERE id IN (
    SELECT id FROM dup WHERE group_size > 1 AND id <> survivor_id
);
-- ---------------------------------------------------------------------------------------------

-- Case-insensitive uniqueness at the DB level, replacing the case-sensitive UNIQUE(user_id, name)
-- from V1 (dropped at the top of the de-duplication block above) -- closes the race documented on
-- CategoryRepository.findByUserIdAndNameIgnoreCaseOrderByIdAsc.
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
