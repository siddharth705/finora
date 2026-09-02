-- "Paid a Person": the 26th system category, and the new home of every structurally-detected
-- person-to-person payment (CategorizationService.P2P_CATEGORY, PersonToPersonTransferDetector).
--
-- Why this category exists at all, rather than continuing to reuse "Transfer": when the detector
-- first shipped it matched every narration naming an individual, and "Transfer" was a reasonable
-- fit for that population. Merchant-rail detection then split off the payments that settle over a
-- merchant-acquiring rail (PhonePe Q-VPAs, PAYTM.S/PAYTM.D, GPay for Business, BharatPe, the
-- merchant-UPI pseudo-branch IFSCs), which deliberately leaves behind the residue: real transfers
-- mixed with everyday payments to individuals being paid FOR something -- a driver paid directly
-- rather than through the app, a maid, a landlord, a tutor, a vegetable seller with no merchant
-- account. Filing all of those as "Transfer" asserts that no spending occurred, which is a
-- confident claim the detector has no evidence for. "Paid a Person" claims only what it does know:
-- money left the account, it went to a named individual, the purpose is unknown.
--
-- This changes the LABEL only, never the arithmetic. Nothing in this codebase excludes spend by
-- category name -- RefundNetting.reportable excludes by ReconciliationStatus, and no dashboard,
-- budget or analytics path keys off a category-name literal. These rows counted as spend under
-- "Transfer" and count as spend here.

-- 1. Seed it for every existing user. AuthService.seedDefaultCategories covers everyone who
--    registers from here on; this covers everyone who already has.
--
--    icon/color match the DEFAULT_CATEGORIES entry exactly ('users' reuses the existing closed
--    CategoryPalette token, in a different colour from Friend Repayment). Set inline rather than
--    left to V118's column defaults ('tag'/'gray'), which would give existing users a differently
--    rendered category from the one new registrations get.
--
--    NOT EXISTS matches on lower(name), not name: V118 replaced V1's case-sensitive
--    UNIQUE(user_id, name) with the case-insensitive uq_categories_user_name_ci, so an existing
--    "paid a person" would collide here and abort the migration -- and with it the backend's boot.
--
--    A user who had already hand-created a category by this name keeps it EXACTLY as it is --
--    their own name casing, their own V118 icon/color choice, and is_system = false. It is
--    deliberately not promoted to a system row the way V118 promoted system-ness onto a
--    de-duplication survivor: V118 was reconciling two rows that had to become one, whereas here
--    promoting would silently strip a user's ability to rename or delete a category they created
--    themselves (CategoryService.rename/delete both 403 on is_system). Routing still finds it --
--    CategorizationService.resolveOrCreateCategory looks up by
--    findByUserIdAndNameIgnoreCaseOrderByIdAsc -- and if they later delete it, that same method
--    recreates it on the next detection.
INSERT INTO categories (id, user_id, name, is_system, icon, color)
SELECT gen_random_uuid(), u.id, 'Paid a Person', true, 'users', 'orange'
FROM users u
WHERE NOT EXISTS (
    SELECT 1 FROM categories c
    WHERE c.user_id = u.id AND lower(c.name) = 'paid a person'
);

-- 2. Move the rows the engine itself filed under the old constant.
--
--    Without this, one narration gets two different labels either side of this deploy, for no
--    reason the user can see. This is NOT the general "backfill the Other backlog" work the design
--    review defers -- it is the rename following its own rows, and decision_source makes that set
--    exactly identifiable rather than guessed at.
--
--    Three conditions, all load-bearing:
--      decision_source = 'STRUCTURAL_P2P'  -- persisted by @Enumerated(EnumType.STRING), so this
--                                             is precisely "the detector chose this, nothing else
--                                             did". A row later re-decided by a rule or by learning
--                                             carries that source instead and is left alone.
--      category_manually_set = false       -- the standing safety gate for every automated
--                                             recategorization in this codebase: a category a
--                                             person chose is never overwritten by a migration.
--      category_id = their "Transfer" row  -- conservative. Only rows still sitting where the old
--                                             constant put them move.
--
--    The user_id predicate below is NOT a fourth condition -- it is redundant, and deliberately
--    kept anyway. `t.category_id = origin.category_id` already scopes the update to one user,
--    because a category id belongs to exactly one user. Removing it was tried against
--    V123PaidAPersonCategoryMigrationIT and changed no outcome. It stays because a cross-user
--    write is the worst thing this statement could do, and stating the scope beats deducing it.
--
--    Worth being explicit about one case this DOES move: a row the user saw during import review
--    and accepted without changing. That is intentional and matches the semantics the codebase
--    already committed to -- CategorizationService.isUnconfirmedGuess treats a row still carrying
--    the category its unconfirmed source produced as still unconfirmed, precisely because
--    accepting a pre-filled default is not a decision. If it were treated as one, the review queue
--    and merchant learning would both have been wrong about it long before this migration.
--
--    Matching by lower(name) on both sides is safe and unambiguous: uq_categories_user_name_ci
--    guarantees at most one row per (user, lowercased name), and system categories cannot be
--    renamed (CategoryService.rename 403s on is_system), so "Transfer" is still spelled that way
--    for every user who has it. A user missing either row simply gets no update.
WITH destination AS (
    SELECT user_id, id AS category_id FROM categories WHERE lower(name) = 'paid a person'
),
origin AS (
    SELECT user_id, id AS category_id FROM categories WHERE lower(name) = 'transfer'
)
UPDATE transactions t
SET category_id = destination.category_id,
    updated_at  = now()
FROM destination
JOIN origin ON origin.user_id = destination.user_id
WHERE t.user_id               = destination.user_id
  AND t.category_id           = origin.category_id
  AND t.decision_source       = 'STRUCTURAL_P2P'
  AND t.category_manually_set = false;
