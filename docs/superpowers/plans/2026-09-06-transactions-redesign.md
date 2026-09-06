# Transactions Page Redesign Implementation Plan

**Goal:** Redesign `frontend/src/pages/Ledger.tsx` (route `/app/transactions`) to match the Transactions mockup Sid supplied — hero header, 4 KPI stat cards, a richer filter bar, scrollable category chips, an Account column with bank branding, and numbered pagination — while preserving every existing behavior (edit/delete, category explanation panel, reconciliation badges, merchant grouping review cards, search-from-TopBar deep link).

**Architecture:** Pure frontend change, one page file + small additions to shared primitives. No new backend endpoints. Reuses `FinoraCard`, `MetricCard`, `Badge`, `IconButton`, `Skeleton`, `EmptyState` from `frontend/src/design-system`, `BankLogo`/`MerchantLogo` from `frontend/src/components`, and the existing `ICON_COMPONENTS`/`COLOR_HEX` category-icon maps from `frontend/src/lib/categoryIcons.ts`.

**Tech Stack:** React 18 + TypeScript, Vite, Tailwind (CSS-variable tokens), TanStack Query, Vitest + Testing Library. The reference prompt asked for static HTML/CSS with no React — not applicable here, since this ships inside the real Fynora SPA and must stay wired to `transactionsApi`, not become a disconnected prototype.

## Source of truth: mockup vs. what this plan builds

The mockup's header/search bar/Add Transaction button/theme/notifications/help icons and the left sidebar (including the "Refer & Earn" widget) are **already implemented globally** in `TopBar.tsx` and `Sidebar.tsx`, rendered once per `/app/*` layout in `App.tsx` — this plan does not touch either file or duplicate their content inside the page.

Palette: dark graphite `#262A33` / `#15171C`, warm cream `#F4F1EC`, navy sidebar — **not** the mockup's purple, per the standing redesign-series decision (see Budgets plan, PR #1009).

Deliberately deferred / adapted, grounded in what the backend actually returns:

- **Hero illustration + "Track today. A brighter tomorrow." quote card** — there's no illustration asset for this page and no copy-asset pipeline to source one honestly; a fabricated raster image would be pure decoration invented for this PR. Replaced with a text-only eyebrow + headline + subtitle, consistent with the rest of the app's chrome-free page bodies.
- **KPI "Transactions: 61" / "Top Category" / "Total Spent"** — computed from a real, bounded query (`transactionsApi.search` with the active keyword/date/type/status filters but no category filter, `size: 500`), not a fabricated aggregate endpoint. `totalElements` from that response is the exact count; total spend and per-category breakdown are reduced client-side from its `content` (same pattern `Budgets.tsx` already uses to reduce a full list client-side). Above 500 matching rows the category breakdown/top-category is approximate over the first 500 — called out in an inline comment, not hidden.
- **"This Month" budget KPI** — reuses `budgetsApi.list()` exactly as `Budgets.tsx` already aggregates it (`spentThisMonth` / `monthlyLimit` sums), since budgets are inherently a calendar-month concept independent of the ledger's own filters.
- **Category filter chips** — built from the user's real `categoriesApi.list()` categories (icon + color already wired into `ICON_COMPONENTS`/`COLOR_HEX`), with counts from the same bounded stats query above, not the mockup's hardcoded category list.
- **Account column** (bank logo + name + masked number) — new: fetches `accountsApi.list()` once into an `id -> Account` map (same pattern `Budgets.tsx` uses for `categoriesById`), renders `BankLogo` + `account.name` + `account.accountNumberMasked`.
- **Status column** — the mockup's Needs Review / Categorized / Recurring / Reviewed values map onto real fields already on `Transaction`: `needsCategoryReview` → Needs Review (warning), else `recurring` → Recurring (primary), else `categoryManuallySet` → Reviewed (primary-tint), else → Categorized (success). The existing reconciliation badge (Duplicate/Transfer/Refund/Reversed/Investment/Superseded) and the "Why this category?" explanation panel are kept as-is, surfaced as a secondary badge in the same cell — this is real behavior already shipped, not something to drop for a visual refresh.
- **Numbered pagination (1 2 3 … 7)** — built inline for this page; there's no shared numbered-pagination primitive yet in `design-system`, and one page doesn't justify extracting one (YAGNI, per repo convention).
- **Rows-per-page selector** — wired to the existing `filters.size`, defaulting to 10 as today.

## Global Constraints

- No new backend endpoints or DTO changes.
- Preserve every existing interaction in `Ledger.tsx`: edit modal, delete + confirm dialog, "Why this category?" / reconciliation explanation modal, `MerchantGroupReviewCard`/`CounterpartyGroupReviewCard`/`AskOnceCard`, TopBar's `?q=` deep link, cache invalidation set on edit/delete (`invalidateEverything`).
- No `Co-Authored-By` / AI-attribution trailer in the commit message (repo-wide rule).
- Reuse design-system primitives; only new addition allowed is extending `Badge` tones if the existing 5 aren't enough for the Status column (check before adding).

## File Structure

- **Modify:** `frontend/src/pages/Ledger.tsx` — the redesign itself.
- **Modify:** `frontend/src/pages/Ledger.test.tsx` — cover new KPI/chip/account-column/pagination behavior; keep all existing test coverage green.
- **Possible modify:** `frontend/src/design-system/Badge.tsx` (+ test) — only if an additional tone is genuinely needed for the Status column.

## Tasks

- [x] Read `Ledger.tsx`, `Ledger.test.tsx`, `Budgets.tsx`, `types/index.ts`, `api/endpoints.ts`, `design-system/*`, `BankLogo.tsx`, `MerchantLogo.tsx`, `categoryIcons.ts`, `TopBar.tsx`, `Sidebar.tsx` to ground every element above in real code (done during planning).
- [x] Add the bounded stats query (`transactionsApi.search` sans category/page, `size: 500`) and derive total spend, transaction count, top category, category chip counts from it.
- [x] Add `accountsApi.list()` fetch → `accountsById` map for the Account column (uses `MaskedAccountNumber`, not a hand-rolled `.slice()`, for the same reveal/auto-remask behavior the rest of the app already gives account numbers).
- [x] Build the page-level header (eyebrow + headline + subtitle) and 4 KPI cards: Total Spent, Transactions, Top Category (all via a custom `FinoraCard`/`MetricCard` composition — no fabricated "vs last month" delta, since the filtered window is arbitrary, not necessarily a calendar month; see the plan's own deferred-elements section), and This Month (real `budgetsApi` aggregation + progress bar).
- [x] Rebuild the filter bar: keyword search, type select, status select, date-from/date-to, plus a real "Clear" action (not decorative) that resets every filter. Category filtering lives in the chip row instead of a redundant select.
- [x] Add the horizontal scrollable category chip row (All + real categories with counts), wired to `filters.categoryId`.
- [x] Rebuild the table: Date (day/month stacked), Description (merchant logo + name + narration + existing counterparty tag), Category (existing auto/manual + why-badge), Account (new), Amount, Status (new derived badge + existing reconciliation badge), Actions (existing edit/delete `IconButton`s).
- [x] Rebuild pagination: numbered buttons (only when >1 page) + rows-per-page select, alongside the existing Previous/Next `IconButton`s.
- [x] Update/extend `Ledger.test.tsx`: added `accountsApi`/`budgetsApi` to the endpoint mock with safe defaults, scoped the OK-row exhaustive button-list assertion to that row (`within`) so it stays robust to new page-level chrome, and added two new test blocks (KPI/chip filtering, account column rendering). Full suite: 1118/1118 passing, `tsc --noEmit` clean, `eslint` clean.
- [ ] Manually verify in a live dev server (loading skeletons, empty state, filters, chips, pagination, edit/delete, explanation modal, dark mode) — **not done**. The Browser-pane preview tool resolves `.claude/launch.json` from the primary checkout, not this worktree, and the primary checkout is read-only for writes per this repo's CLAUDE.md; the shared dev backend already running on :8080 (another session's) also 403s requests from this worktree's own dev-server origin (CORS/CSRF origin allowlist). Verification here is TypeScript + ESLint + the full Vitest/RTL suite (which renders the real component tree and asserts on real DOM), not a live-browser check.
