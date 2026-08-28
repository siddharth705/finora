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

```sql
-- V<next>__category_customization.sql
ALTER TABLE categories
    ADD COLUMN icon  VARCHAR(40),   -- lucide-react icon name, e.g. "piggy-bank"; NULL = use the
                                     -- existing frontend CATEGORY_ICON name-keyed fallback map
    ADD COLUMN color VARCHAR(9);    -- hex, e.g. "#7C3AED"; NULL = deterministic color-from-name,
                                     -- same "hash the string, pick a preset swatch" approach avatars use

CREATE UNIQUE INDEX uq_categories_user_name_ci
    ON categories (user_id, lower(name));
-- replaces the case-sensitive UNIQUE(user_id, name) from V1 -- case-insensitive
-- uniqueness enforced at the DB, not just app-level checked-then-created.
```

Both new columns are nullable and additive — system categories keep
rendering exactly as they do today (frontend's hardcoded `CATEGORY_ICON` map,
`Dashboard.tsx:74`, untouched). Only user-created categories populate them.

## Backend API

New endpoints on `CategoryController` (today: `GET /categories` only):

- `POST /categories` — `{ name, icon?, color? }`. Rejects if `isSystem`-named
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

- Type-ahead filter over `categoriesApi.list()`.
- If the typed text doesn't exactly match an existing category (case-
  insensitive), show a trailing `+ Create "{text}"` row.
- **Duplicate suggestions:** while typing, run a lightweight client-side
  similarity match (normalized Levenshtein ratio, threshold ~0.6) against the
  already-fetched category list — no new endpoint, the list is small
  (typically under a few dozen per user). Near-matches surface above the
  `+ Create` row as "Did you mean: SIP, Investments?" so the user can pick an
  existing one instead of creating a near-duplicate; they can still create
  their own if none fit.
- Selecting `+ Create` opens a small inline panel: name (prefilled from the
  typed text), an icon grid (a curated ~24-icon subset of `lucide-react`,
  matching what `Dashboard.tsx`'s `CATEGORY_ICON` map already draws from),
  and a color swatch row (8–10 presets pulled from the existing design
  tokens, not a full picker). Submits `POST /categories`, then selects the
  new category.
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
  case-insensitive duplicate rejection, and the rename/delete cascade to
  matching `CategoryRule` rows.
- `TransactionGroupingServiceTest`-style unit coverage for the delete
  transaction (reassignment across transactions + budget + rules in one go,
  rollback on partial failure).
- Frontend: `CategoryCombobox` unit tests for the create-row affordance,
  duplicate-suggestion threshold, and that system categories never render
  edit/delete controls.
