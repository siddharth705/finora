# Custom transaction categories

Status: design, not yet implemented.

## Goal

Today every category picker (Ledger edit modal, AskOnceCard, MerchantGroupReviewCard)
is a plain `<select>` populated from `GET /categories` — a fixed list seeded per
user at registration. A user whose merchant doesn't fit any of those has no
escape hatch besides mislabeling it "Other." This adds a "+ Create '{typed
text}'" affordance to that same combobox everywhere it appears, backed by a
new create/rename/delete API, with full lifecycle management (not just
create).

## Category model: system vs. user

`Category` already has `isSystem` but nothing enforces it. This design makes
the split real:

- **System categories** (`isSystem = true`, the seeded default set) —
  immutable. No rename, no delete, in the API or UI. They're what the 43
  seeded global `CategoryRule` rows match against by name
  (`V19__category_rules_global_seed.sql`); keeping them permanently
  unrenameable means those global rules keep matching by name safely forever,
  with zero changes to the rule engine. This is why the rule-ID migration
  considered during design (see **Parked** below) turned out to be
  unnecessary for this feature.
- **User categories** (`isSystem = false`) — full CRUD. Created inline from
  any category combobox; renameable and deletable from the same place (no
  separate "manage categories" screen needed, since create/rename/delete are
  all reachable from the combobox's existing per-category affordances).

Uniqueness stays **per-user**, not global (`UNIQUE(user_id, name)` already
exists, `V1__init_schema.sql:36-42`) — two different users can each have their
own "SIP" meaning different things, matching how every other personal-finance
app handles this. The only schema change here is making that constraint
case-insensitive, closing the documented race in
`CategoryRepository.findByUserIdAndNameIgnoreCaseOrderByIdAsc` where two
concurrent requests creating same-name-different-case categories can both
succeed today.

## Data model changes

Icon and color are **curated tokens**, not free-form values — a fixed backend
enum-like set, not arbitrary lucide names or hex codes. This keeps every
category (system and user) visually consistent and means the picker UI is
always rendering from the same finite palette:

```sql
-- V<next>__category_customization.sql
ALTER TABLE categories
    ADD COLUMN icon  VARCHAR(30) NOT NULL DEFAULT 'tag',   -- token, e.g. "chart", "home", "utensils"
    ADD COLUMN color VARCHAR(20) NOT NULL DEFAULT 'gray';  -- token, e.g. "blue", "purple", "teal"

-- Backfill the ~15 seeded system categories with real tokens (not left at the
-- generic default) using the same name -> icon mapping the frontend's
-- CATEGORY_ICON map already encodes, e.g.:
--   UPDATE categories SET icon = 'shopping-bag', color = 'purple' WHERE is_system AND name = 'Shopping';
--   UPDATE categories SET icon = 'utensils',     color = 'orange' WHERE is_system AND name = 'Dining';
--   ... (one UPDATE per seeded name, values chosen to match current rendering)

CREATE UNIQUE INDEX uq_categories_user_name_ci
    ON categories (user_id, lower(name));
-- replaces the case-sensitive UNIQUE(user_id, name) from V1 -- case-insensitive
-- uniqueness enforced at the DB, not just app-level checked-then-created.
```

`icon`/`color` are `NOT NULL` with defaults — every category, system or user,
always has a real value. Both columns are validated against a curated allow-
list at the service layer (a plain `Set<String>` constant, not a DB enum
type, so adding a new icon later is a one-line change, not a migration).

**Retiring the frontend hardcoded map:** `Dashboard.tsx`'s `CATEGORY_ICON`
(`Dashboard.tsx:74`, keyed by category *name*) is replaced by rendering
`category.icon`/`category.color` directly from the API response — the
backend becomes the single source of truth instead of a frontend map that
only knows about the original seeded names and falls back to a generic icon
for anything else (including, previously, every custom category).

A new `GET /categories/options` endpoint returns `{ icons: [...], colors:
[...] }` (each an ordered list of `{ token, label }`) so the icon
grid/color-swatch picker renders from the same allow-list the backend
validates against, instead of duplicating the list in the frontend.

## Backend API

New endpoints on `CategoryController` (today: `GET /categories` only):

- `GET /categories/options` — `{ icons: [{token,label}], colors: [{token,label}] }`,
  the curated allow-lists the picker UI renders from.
- `POST /categories` — `{ name, icon?, color? }`. `icon`/`color` default to
  `"tag"`/`"gray"` if omitted; either must be a valid token from the
  allow-list if provided (400 otherwise). Rejects if `isSystem`-named
  collision or case-insensitive duplicate for this user (409). Rejects blank/
  overlong names (`VARCHAR(80)` ceiling already on the column). Returns the
  created `Category`.
- `PATCH /categories/{id}` — `{ name?, icon?, color? }`. 403 if `isSystem`.
  On name change: in the same transaction, bulk-update every `CategoryRule`
  row where `scope = 'USER'`, `user_id = <this user>`, `action_type IN
  ('ASSIGN_CATEGORY','MARK_INVESTMENT')`, and `action_value` case-insensitively
  matches the old name, to the new name. (Global rules are untouched — they
  only ever reference system-category names, which never change.)
- `DELETE /categories/{id}?reassignTo={targetCategoryId}` — 403 if `isSystem`.
  `targetCategoryId` is required whenever the category has any dependents;
  omitting it is only valid for a category with zero transactions, zero
  budgets, and zero personal rules. In one transaction: reassign
  `transactions.category_id`, delete or repoint the one possible
  `budgets` row (`UNIQUE(user_id, category_id)` means at most one), rewrite
  matching `CategoryRule.action_value` rows the same way rename does, then
  delete the category.
- `GET /categories/{id}/usage` — `{ transactionCount, budget: {...} | null,
  ruleCount }`. Backs the delete-confirmation dialog's "used by N
  transactions, 1 budget, 2 rules" summary before the user picks a
  reassignment target.

`resolveOrCreateCategory` (`CategorizationService.java:399-414`) is untouched
— it remains the internal server-side path (import, rule matching, bulk
recategorize) and is unrelated to this user-facing create flow.

## Frontend

One shared component, `CategoryCombobox`, replaces the plain `<select>` in
all three consumers (`Ledger.tsx` edit modal, `AskOnceCard.tsx`,
`MerchantGroupReviewCard.tsx`):

Typing filters the list in strict priority order, so the create option is
never the first thing offered:

1. **Exact/prefix matches** against `categoriesApi.list()` — shown first,
   ranked as today.
2. **Fuzzy suggestions** — a lightweight client-side similarity match
   (normalized Levenshtein ratio, threshold ~0.6) against the same
   already-fetched list, no new endpoint needed (the list is small, typically
   under a few dozen per user). Near-matches the exact-filter missed surface
   here labeled "Did you mean: SIP, Investments?" — one more chance to reuse
   an existing category before creating a near-duplicate.
3. **`+ Create "{text}"`** — only shown once (1) and (2) have had their say,
   as the last row. Selecting it opens a small inline panel: name (prefilled
   from the typed text), an icon grid, and a color swatch row, both rendered
   from `GET /categories/options`. Submits `POST /categories`, then selects
   the new category.

Beyond the typing flow:

- Each user-created category row in the list gets a small edit/delete
  affordance (pencil/trash icon on hover) opening the same inline panel
  (rename) or the delete-confirmation dialog. System categories show neither.
- Delete confirmation dialog: calls `GET /categories/{id}/usage` first,
  renders the counts, then a target picker (`CategoryCombobox` again, scoped
  to "existing categories other than this one" + its own inline create) that
  becomes the `reassignTo` param. Confirm is disabled until a target is
  picked (whenever the category has any dependents).

## Rename/delete blast radius (from investigation)

- `Transaction.categoryId` / `Budget.categoryId` are FK-by-UUID — safe under
  rename with no code changes needed there.
- `CategoryRule.actionValue` is the one name-string reference that rename/
  delete must actively rewrite (see API section above). Confirmed via full
  read/write-site audit: no self-service rule UI exists today (rules are
  admin/support-authored per user via `AdminUserRuleController`), so the
  blast radius per rename is "however many personal rules that one admin
  wrote for that one user" — small and bounded, not a bulk data migration.
- `TransactionExplanationService`/`AdminSearchService` read `actionValue` for
  display only; once the cascade above keeps it in sync, no changes needed
  there.
- Audit log entries (`TransactionService.updateCategory`) already store the
  category name as a point-in-time string — expected/correct for an audit
  trail, not something a rename should retroactively rewrite.

## Future: category merge

Not built now, but this design doesn't need to change shape to support it
later. "Merge 'Mutual Fund SIP' into 'SIP'" is functionally identical to the
delete flow already specified: `DELETE /categories/{Mutual Fund SIP id}
?reassignTo={SIP id}` already reassigns every transaction, the one possible
budget, and every matching personal rule from the source to the target, then
removes the source category. A future "Merge categories" entry point would
just be a friendlier UI (e.g. a "merge into..." action alongside delete, or a
multi-select-then-merge flow) calling this same endpoint — no new backend
work is anticipated.

## Parked: rule `category_id` migration

Considered during design as an alternative to the rename-cascade approach:
add `category_rules.action_category_id UUID REFERENCES categories(id)` and
migrate `ASSIGN_CATEGORY`/`MARK_INVESTMENT` rules off name-string matching
entirely.

**Not pursued for this feature** — it turned out to be structurally blocked
for the 43 global rules: they have `user_id IS NULL` and match by name across
every user, but `categories` rows are always per-user, so there is no single
category row a global rule could point at by ID. Backfilling only the
user-scope rows would leave the migration permanently half-finished. Making
system categories immutable (this design) removes the actual pain the
migration was meant to solve — global rules never need their name reference
to survive a rename, because system category names can't change. If a future
need for ID-based rule matching arises independent of this, it should start
from "how should a global rule resolve to a per-user category" as its own
design question, not as a follow-on to this one.

## Testing

- Backend: `CategoryControllerIT` (or extend existing category tests) for
  create/rename/delete happy paths, 403 on `isSystem` mutation attempts,
  case-insensitive duplicate rejection, icon/color token validation
  (rejects an unlisted token), and the rename/delete cascade to matching
  `CategoryRule` rows.
- `TransactionGroupingServiceTest`-style unit coverage for the delete
  transaction (reassignment across transactions + budget + rules in one go,
  rollback on partial failure).
- Frontend: `CategoryCombobox` unit tests for the create-row affordance,
  duplicate-suggestion threshold, and that system categories never render
  edit/delete controls.
