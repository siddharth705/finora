# Adversarial review fixes — custom transaction categories

Branch: `worktree-fix-merchant-group-duplicate-count`
Worktree: `/Users/sid/Downloads/finora/.claude/worktrees/fix-merchant-group-duplicate-count`

## Commits

| SHA | Subject |
| --- | --- |
| `a1cf264e` | fix(backend): de-duplicate case-variant categories before V118's unique index |
| `1a07da58` | fix(backend): count merchant-learning rows as a category delete dependent |
| `74a2e555` | fix(backend): 409 on a raced category name, 400 on an explicitly blank rename |
| `69d19223` | fix(frontend): surface usage-fetch failures and stop offering a create that 409s |

---

## Finding 1 — `CREATE UNIQUE INDEX` could abort on real data

`backend/src/main/resources/db/migration/V118__category_customization.sql:5-260`

### What the FK structure actually is (verified, not assumed)

`grep -rn "REFERENCES categories" backend/src/main/resources/db/migration` returns exactly
seven columns across four migrations. Read directly from the migration sources:

| Column | Migration | ON DELETE | Constraint that could collide on a repoint |
| --- | --- | --- | --- |
| `merchant_category_map.category_id` | V1:48 | CASCADE | `UNIQUE(user_id, normalized_desc)` — `category_id` not part of it, cannot collide |
| `transactions.category_id` | V1:57 | SET NULL | none |
| `budgets.category_id` | V1:82 | CASCADE | `UNIQUE(user_id, category_id)` |
| `merchant_category_learning.category_id` | V7:41 | CASCADE | `UNIQUE(user_id, merchant_id, category_id)` |
| `merchant_learning_audit.previous_category_id` | V7:59 | (NO ACTION) | none |
| `merchant_learning_audit.new_category_id` | V7:60 | (NO ACTION) | none |
| `merchant_learning_events.category_id` | V62:28 | CASCADE | none (V62 has only two non-unique indexes) |

Two of these were **not** in the review prompt's list and were found by reading the schema:
`merchant_category_map` (V1) and `merchant_learning_events` (V62). Both are repointed.
`category_rules.action_value` is a name string and is deliberately untouched — every lookup of it
(`findByUserIdAndActionTypeInAndActionValueIgnoreCase`) is already case-insensitive.

### The de-duplication

Survivor = lowest `id` per `(user_id, lower(name))` group, mirroring
`CategorizationService.resolveOrCreateCategory:519-522` (`findByUserIdAndNameIgnoreCaseOrderByIdAsc`
→ `matches.get(0)`). So the row kept is the row the running system already resolves duplicates to.

Written as plain SQL (`WITH` CTE + `UPDATE`/`DELETE`), matching the codebase's style: a
`grep -l 'DO \$\$'` over all 113 migrations returns nothing — no migration in this repo uses
PL/pgSQL, so a `DO $$ … $$` block would have been the odd one out.

Order of operations, and why each step is safe:

1. **`DROP CONSTRAINT categories_user_id_name_key` moved to the top of the block.** This was a real
   bug found by the test suite mid-implementation: step 2 promotes a system row's canonical
   spelling onto its survivor, which briefly puts two rows at the same exact name — which V1's
   still-live case-sensitive constraint rejected. Dropping first removes the conflict; the new
   case-insensitive index is still created last.
2. **System-ness promotion.** A group can legitimately mix a seeded system row (`Dining`) and a
   user/import-created one (`dining`), and the survivor may be the non-system one. Rather than
   deviate from the lowest-id rule, `is_system` and the system row's name are copied onto the
   survivor. This also keeps the icon/color backfill further down the file (which matches on
   `is_system AND name = '…'`) working for that user.
3. **Repoints with no collision risk:** `merchant_category_map`, `transactions`, both
   `merchant_learning_audit` columns, `merchant_learning_events`. The audit columns are
   *repointed*, not NULLed as `CategoryService.delete` does — there the category genuinely ceases to
   exist; here it survives under one id, so the trail stays truthful.
4. **`budgets`:** rank the group's budget rows (survivor's own first, then by `category_id`, then by
   `budgets.id`), delete all but rank 1, then repoint the keeper. Deleted rather than merged
   because summing two monthly limits invents a limit the user never set. Ordering by
   `category_id` rather than the budget row's own random id makes which limit survives
   deterministic.
5. **`merchant_category_learning`:** same ranking, but with a merge — the keeper absorbs
   `sum(confirmation_count)` and `max(last_confirmed_at)` for its `(user, merchant, group)` and
   takes the survivor's id; the rest are deleted. Identical semantics to
   `MerchantLearningService.repointCategory:397-429`, which solves this exact collision at runtime.
6. **Confidence recompute** across every touched merchant's whole distribution, matching
   `ConfidenceEngine.recomputeDistribution` (share of total, rounded; 0 when total is 0) — merging
   shifts every *other* category's share of that merchant's total too.
7. **Delete the loser category rows.** By now nothing references them, so the remaining CASCADEs
   take nothing with them.

### Reasoning: would this survive real duplicate data with FK references from multiple tables?

- The loser→survivor mapping is recomputed independently in each statement, from `categories`
  alone. `categories` is not modified between statements except for the survivor's own
  `name`/`is_system`, and only within its own group — `lower(name)`, the partition key, is
  unchanged. So every statement sees the same mapping. This is the property the whole sequence
  depends on, and it is stated in the file's own comment.
- The learning DELETE recomputes `ranked` *after* the merge UPDATE has already moved the keeper to
  `category_id = survivor_id`. That still sorts the keeper first in its partition
  (`(l.category_id = g.survivor_id) DESC`), so `rn = 1` selects the same row and `rn > 1` is still
  exactly the absorbed rows. Verified by the three-way-duplicate test.
- Idempotent: after one run no group has `group_size > 1`, so `grp` is empty and every statement is
  a no-op.
- **One implementation bug the tests caught:** `min(id)` — Postgres has no `min()` aggregate for
  `uuid`. Replaced with `first_value(id) OVER (… ORDER BY id)`, which is the same value and uses
  uuid's native ordering.

### Test

`backend/src/test/java/com/finora/service/V118CategoryDedupMigrationIT.java` (new, 12 tests).

The codebase already had a pattern for this — `V74`/`V79`/`V97` migration ITs each stand up their
own `PostgreSQLContainer` and `Flyway` instance (Spring Boot's autoconfiguration migrates straight
to head with no hook to pause). This follows it: migrate to V115 (the highest version below V118 —
V116/V117 never existed), seed rows at that schema shape by hand, then run V118 forward.

Cases covered: empty schema; a bare duplicate pair; the index is genuinely enforced afterwards;
transactions + `merchant_category_map` repointed; both rows have a budget; only the loser has a
budget; learning rows merged (counts summed, later timestamp wins); learning row only on the loser
with confidence recomputed across the merchant (75/25); audit + queued event repointed (the audit
FKs are NO ACTION — they would *refuse* the delete if missed); a system row in a mixed group;
a three-way duplicate with references on every row and budgets on two losers; and two different
users with the same name not being conflated.

One subtlety worth recording: the test's `pgOrderedIds` helper deliberately sorts by
`UUID::toString`, not `UUID::compareTo`. Java compares the two halves as *signed* longs, so a uuid
with the high bit set sorts negative there, while Postgres compares the 16 bytes unsigned. Sorting
by the canonical hex string matches Postgres. Using `compareTo` would have made these tests
intermittently assert against the wrong survivor.

Result: **12/12 pass.**

---

## Finding 2 — merchant-learning-only dependents allowed a targetless delete

- `backend/src/main/java/com/finora/dto/CategoryUsageDto.java:10` — added `learningRowCount`.
- `backend/src/main/java/com/finora/repository/MerchantCategoryLearningRepository.java:52-59` —
  added `countByUserIdAndCategoryId`, mirroring `TransactionRepository.countByUserIdAndCategoryId`.
- `backend/src/main/java/com/finora/service/MerchantLearningService.java:253-265` — added
  `learningRowCount(userId, categoryId)`. Exposed through the service rather than by injecting the
  repository into `CategoryService`, so the Learning Engine's tables stay behind the same seam its
  delete-time cleanup (`onCategoryDeleted`) already goes through.
- `backend/src/main/java/com/finora/service/CategoryService.java:119` — included in `usage()`.
- `backend/src/main/java/com/finora/service/CategoryService.java:148-157` — included in
  `hasDependents`.
- `frontend/src/api/endpoints.ts:645-653` — response type.
- `frontend/src/components/CategoryDeleteDialog.tsx:6-13, 40-42, 71-75` — counted in
  `hasDependents` (which is what gates both the reassignment picker and `canDelete`), and rendered
  as "N learned merchants" in the usage list.

Traced through: with only learning rows present, `hasDependents` is now true, so a `null`
`reassignTo` 400s. When a target *is* supplied, the `hasDependents` branch still runs
`reassignCategory` / budget delete / rule rewrite — all no-ops here (0 transactions, no budget, no
rules) — and `onCategoryDeleted` then reaches `repointCategory`, which is the path that actually
moves the training data. That is the behaviour the machinery was built for.

Tests: `CategoryServiceTest` — `usageReportsTransactionBudgetRuleAndLearningCounts`,
`deleteRequiresAReassignTargetWhenTheOnlyDependentIsMerchantLearningData`,
`deleteWithNoDependentsAtAllStillNeedsNoTarget` (the negative control: a genuinely empty category
must still delete with no target). `CategoryDeleteDialog.test.tsx` — a new case asserting the
picker appears and Delete stays disabled when learning rows are the only dependent.

---

## Minors

All five addressed.

1. **`DataIntegrityViolationException` → 409.**
   `CategoryService.java:216-228` — `saveRejectingDuplicates`, used by both `create()` and
   `rename()`. Uses `saveAndFlush`, not `save`: inside `@Transactional` a plain `save` may not
   flush until commit, at which point the violation is thrown outside the `try` and can no longer
   be translated. Tests: `createTranslatesAUniqueIndexViolationIntoTheSameConflict`,
   `renameTranslatesAUniqueIndexViolationIntoTheSameConflict`.
2. **`PATCH {"name": ""}` → 400.** `CategoryService.java:81-92` — the guard is now `newName != null`
   rather than `newName != null && !newName.isBlank()`, so an explicitly supplied blank name goes
   through `validateName` and 400s, while an absent one (record component is `null`; confirmed in
   `CategoryController.UpdateCategoryRequest`) still skips the rename. Tests:
   `renameRejectsAnExplicitlyBlankName`, plus `renameStillLeavesTheNameAloneWhenItIsAbsentFromThePatch`
   as the negative control.
3. **`['transactions']` invalidation on edit.** `CategoryCreateEditPanel.tsx:44-51` — added, gated
   on `mode === 'edit'` (a create cannot affect existing transaction rows). Test asserts both keys
   after an edit and only `['categories']` after a create.
4. **Visible usage-fetch error.** `CategoryDeleteDialog.tsx:27-40, 75-79` — a `usageFailed` flag and
   a `text-warning` notice, matching `CategoryCombobox.tsx:175-179`'s existing convention for its
   own failed fetch. Test: `shows a visible notice when the usage fetch fails`.
5. **`exactNameMatch` over the unfiltered list.** `CategoryCombobox.tsx:89-98` — only that
   existence check now reads `categories` instead of `pool`; the selectable rows (`exactMatches`,
   `fuzzySuggestions`) still exclude `excludeCategoryId`. Test:
   `does not offer to create a category whose name is the excluded one`.

Also fixed in passing, because the repo's pre-commit eslint hook flags them on any touched file:
three pre-existing `@typescript-eslint/no-floating-promises` errors on `invalidateQueries` calls in
`CategoryDeleteDialog.tsx` and `CategoryCreateEditPanel.tsx` (added `void`).

---

## Test results

| Suite | Result |
| --- | --- |
| `V118CategoryDedupMigrationIT` (new) | 12/12 pass |
| `CategoryServiceTest` | 18/18 pass (was 10) |
| `CategoryControllerIT` | 5/5 pass |
| `CategoryDeleteDialog.test.tsx` | 8/8 pass (was 5) |
| `CategoryCreateEditPanel.test.tsx` | 3/3 pass (was 2) |
| `CategoryCombobox.test.tsx` | 11/11 pass |
| `tsc --noEmit` (whole frontend) | clean |
| `eslint` on all touched frontend files | clean |

Full backend / full frontend suites were not run, per the brief.

## Files changed

```
backend/src/main/resources/db/migration/V118__category_customization.sql
backend/src/test/java/com/finora/service/V118CategoryDedupMigrationIT.java   (new)
backend/src/main/java/com/finora/dto/CategoryUsageDto.java
backend/src/main/java/com/finora/repository/MerchantCategoryLearningRepository.java
backend/src/main/java/com/finora/service/MerchantLearningService.java
backend/src/main/java/com/finora/service/CategoryService.java
backend/src/test/java/com/finora/service/CategoryServiceTest.java
frontend/src/api/endpoints.ts
frontend/src/components/CategoryDeleteDialog.tsx
frontend/src/components/CategoryDeleteDialog.test.tsx
frontend/src/components/CategoryCombobox.tsx
frontend/src/components/CategoryCombobox.test.tsx
frontend/src/components/CategoryCreateEditPanel.tsx
frontend/src/components/CategoryCreateEditPanel.test.tsx
```

## Not fully addressed

Nothing from the brief was left undone.

## Concerns

1. **Budget loss is real, if small.** When both rows of a duplicate group carry a budget, one
   monthly limit is permanently deleted with no notification. There is no defensible merge (summing
   invents a number), and the alternative — failing the migration — is worse. Worth knowing before
   this runs against production; the number of affected rows can be checked in advance with a
   `SELECT` over `(user_id, lower(name))` groups having `count(*) > 1`.
2. **`saveAndFlush` changes flush timing** in `create()`/`rename()`. Within a single-entity
   transaction this is benign, but it is a behaviour change beyond pure error translation. Both
   methods write only the one category (the rule rewrites in `rename()` still use plain `save`), so
   nothing is flushed early that would not have been committed anyway.
3. **The migration cannot be tested against actual production data from here.** The IT proves the
   logic against every shape I could construct; it cannot prove production has no shape I did not
   think of. A dry run against a restored production snapshot before deploy would close that gap —
   the migration is a pure data transform inside Flyway's transaction, so a failed dry run rolls
   back cleanly.
4. **Confidence recompute is slightly wider than strictly necessary** — it sweeps in merchants that
   had a survivor row but no merge. For those it recomputes the value they already had (or corrects
   it, if it was stale). Harmless, but it means the migration writes to more rows than the merge
   alone would.

---

# Round 2 — pre-deploy safety gaps (2026-08-29)

Two "Important" findings from the independent review of V118, plus one minor. The migration's
correctness against the six traced duplicate scenarios was already verified by that reviewer and
was not re-derived here; this round only closes the safety gaps.

## Finding 1 — dropped duplicate budgets were unrecoverable and unlogged

`backend/src/main/resources/db/migration/V118__category_customization.sql:145-172` — added a
`CREATE TABLE v118_dropped_budgets AS ...` immediately before the budget-deduplication `DELETE`
(now at :174-187). It reuses the DELETE's own `dup`/`grp`/`ranked` CTEs and selects the `rn > 1`
rows joined back to `budgets`, capturing `budget_id`, `user_id`, `original_category_id`,
`surviving_category_id`, `monthly_limit`, `created_at`, `updated_at`, `dropped_at`. Because it runs
before the DELETE and against the same ranking, it captures exactly the set that is about to be
removed. No PL/pgSQL, matching the file's CTE-only style. Nothing drops the table — a leading
comment says so explicitly.

Cost when there are no duplicates: an empty `CREATE TABLE AS` over a `grp` CTE that yields no rows.

Test: extended `bothRowsHaveABudget_theSurvivorsIsKeptAndTheLosersIsDropped`
(`backend/src/test/java/com/finora/service/V118CategoryDedupMigrationIT.java:318-348`) to assert the
table holds exactly one row whose `budget_id`, `user_id`, `original_category_id`,
`surviving_category_id` and `monthly_limit` (`1200.00`) match the row that was actually deleted.
`seedBudget` now returns the generated id so the test can assert on it (existing callers unchanged).

## Minor — `DROP CONSTRAINT IF EXISTS`

`V118__category_customization.sql:61`, with a three-line comment naming `baseline-on-migrate: true`
as the reason it is not purely theoretical. Folded into the Finding 1 commit.

## Finding 2 — no coverage for a populated, duplicate-free schema

Added `aPopulatedDuplicateFreeSchema_isLeftEntirelyUntouchedApartFromTheIconColorBackfill`
(`V118CategoryDedupMigrationIT.java:478-585`), plus a `DEFAULT_CATEGORIES` fixture (:446-476)
mirroring `AuthService.DEFAULT_CATEGORIES` name→icon→color, in the same order, so drift between the
two is visible side by side.

Seeds: one user with all 25 system categories, two user-created ones ("Fuel", "gym"), an account, a
merchant, two transactions, a budget, two learning rows (deliberately seeded 64/36 — values the
share-of-total formula would NOT produce), a merchant_category_map row, an audit row and a queued
learning event. No case-variant duplicate anywhere.

Asserts after `target("118")`: row counts unchanged in all seven referencing tables;
`v118_dropped_budgets` exists and is empty; every system category kept its id, exact name and
`is_system`, and got exactly the AuthService icon/color; both user-created rows kept the column
defaults `tag`/`gray`; both transactions, the budget (`category_id` + `8000.00`), the map row, the
audit row's two FKs and the event all still point where they did; the learning rows kept
`confirmation_count`, `last_confirmed_at`, and — importantly — their 64/36 confidences, proving the
confidence sweep does not reach a merchant with no survivor.

## Testing

Only `V118CategoryDedupMigrationIT` was run, per instructions.

- Finding 1 RED: with the `CREATE TABLE` block temporarily removed from the migration, the extended
  budget test errors with `relation "v118_dropped_budgets" does not exist` (12 tests, 1 error).
  Migration restored from a scratchpad copy and re-verified.
- Finding 1 GREEN: 12 tests, 0 failures, 0 errors.
- Finding 2 GREEN: 13 tests, 0 failures, 0 errors.

## Files changed

- `backend/src/main/resources/db/migration/V118__category_customization.sql`
- `backend/src/test/java/com/finora/service/V118CategoryDedupMigrationIT.java`

## Commits

- `d6c92362` fix(backend): record V118's dropped duplicate budgets instead of losing them silently
- `26b5a958` test(backend): cover V118 on a populated, duplicate-free schema

## Concerns

1. `v118_dropped_budgets` is an unowned, un-modelled table — no JPA entity, no repository, no
   admin surface. It is discoverable only by someone who knows to look, or by reading this
   migration. If the intent is that a human actually notices a non-empty capture, something has to
   surface it; the table alone is a record, not an alert.
2. Its name is version-stamped, so it is inert after V118 and will sit in `public` forever. That is
   the stated intent, but any future schema-diff or drift tooling will flag it.
3. Finding 2's test asserts icon/color match AuthService's map by way of a hand-copied fixture in
   the test file. If someone edits `AuthService.DEFAULT_CATEGORIES` without touching V118, this
   test catches it — but if they edit both and not the fixture, it catches that too and will read
   as a false failure. That is the intended trade, just worth knowing.
4. Categories cannot be seeded with icon/color before the migration (V118 adds those columns), so
   "unchanged icon/color" is not assertable for the pre-existing rows; the test asserts the
   backfilled values instead, which is the strongest available form of that check.
