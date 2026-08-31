# Animation & Loading-State Polish — Roadmap Proposal

**Status:** APPROVED. Implementation in progress, phased per "Sequencing" below.
**Scope:** `frontend/` (main user app) + `admin-portal/`, logged-in app pages only.
**Explicitly excluded:** landing/marketing pages (own cinematic treatment already, PR #294 etc.),
`mobile/` (different animation system entirely), admin-portal's dense operator tables (Users list,
Audit Log, Learning Queue — table bodies only, see §4).

## Why this order

Two research passes (one per app) surveyed every in-scope page for its current button styling,
loading-state pattern, and existing animation usage. Two things fell out that reshape the naive
"just add motion everywhere" plan:

1. **Three pages have an actual bug, not a polish gap.** `Budgets.tsx`, `Goals.tsx`, and
   `Setup.tsx` have no loading flag at all — they render the "nothing here yet" empty state
   immediately on mount, then pop to real content once the fetch resolves. Every visit flashes the
   wrong UI. This is a correctness fix, and it's nearly free once the shared `Skeleton` primitive
   exists (§1), so it's folded into Phase 1 rather than treated as separate polish work.
2. **`admin-portal` doesn't have `framer-motion` installed at all.** `frontend/` does
   (`^13.1.1`), used today in exactly three components (`AskOnceCard`, `MerchantGroupReviewCard`,
   `CategoryCreateEditPanel`) for one thing: `whileTap={{ scale: 0.96 }}` on primary buttons.
   Nothing uses `whileHover` anywhere in either app. Bringing admin-portal to parity means adding a
   new dependency — a real, visible change, called out explicitly rather than snuck in.

## §1. Foundation — shared primitives (build once, use everywhere)

Neither app has a `<Button>` or general-purpose `<Skeleton>` component today. `ReviewCardSkeleton`
(shipped in PR #682) is the only skeleton in either codebase, and it's hardcoded to one row shape.
Everything else is a bare `<button className="...">` with hand-copied Tailwind classes, repeated
per page.

**Per app** (not shared between them — `frontend/` and `admin-portal/` are separate builds with
separate `package.json`s, no shared package exists), add to each app's design-system directory
(`frontend/src/design-system/`, and a new `admin-portal/src/design-system/` — admin-portal doesn't
have one yet). **Anti-drift rule**: the two implementations should stay structurally identical where
possible — not a shared package, but a convention: a bug fix or API addition to one (a new prop, a
keyboard-focus fix, whatever) should prompt a check of whether the other needs the same change,
rather than silently drifting apart over time.

- **`<Button>`** — wraps `motion.button`. Variants matching what's already informally in use
  (`primary` = `bg-primary text-on-primary`, `secondary` = bordered, `danger` = `text-danger`
  bordered/filled), a `loading` prop that swaps in a `Loader2`/`animate-spin` icon and disables the
  button (the exact pattern `CategoryCreateEditPanel`'s Save button already uses inline — this just
  makes it reusable instead of copy-pasted). `whileTap={{ scale: 0.96 }}` (the established
  convention) is universal. `whileHover` is **not** — it's an opt-in `hoverScale` prop
  (`whileHover={{ scale: 1.02 }}` when set), deliberately not the default. Scaling on hover reads as
  a nice accent on a handful of prominent actions; on every one of hundreds of buttons across the
  app it becomes noticeable in a way tap-scale doesn't (tap is momentary and tied to a click the user
  just made; hover fires on every mouse pass, including ones with no intent to click). Initial
  `hoverScale` adopters: the Dashboard floating `+` button, Quick Action tiles, and primary
  page-level CTAs — not row actions, not every secondary button.
- **`<IconButton>`** — a **separate** component from `<Button>`, not a `<Button iconOnly>` prop.
  Square, icon-only, for the row-action/pagination/refresh use case that's already common on Ledger,
  Settings, and nearly every admin-portal list page. Keeping these as two components instead of one
  growing prop surface (`iconOnly`/`pagination`/`compact`/`toolbar`/`ghost`...) avoids the prop-
  explosion `<Button>` would otherwise accumulate as it gets used on every page in §3/§4 — each
  component stays small enough to actually reason about. Contract: `aria-label` is a **required**
  prop (not optional) — an icon with no accessible name is the single easiest accessibility bug to
  introduce with this component, so make it a type error to omit rather than a review-time catch.
  Same `loading` support as `<Button>` (icon swaps to `Loader2`/`animate-spin`, button disables).
  Keyboard-accessible for free from being a real `<button>` under the hood — no custom
  `role`/`tabIndex` wiring needed.
- **`<Skeleton>`** — a small set of primitive shapes (`Skeleton.Text`, `Skeleton.Block`,
  `Skeleton.Circle`) plus `Skeleton.Row` (generalizing `ReviewCardSkeleton`'s row shape),
  `Skeleton.Card` (for KPI-tile/stat-card grids), and **`Skeleton.Chart`** (one fixed-height
  placeholder — a few animated bars or a flat placeholder curve — reused by every chart on Dashboard
  and Investments, not a bespoke "Cash Flow skeleton" / "Category skeleton" / "Investment skeleton"
  per chart). Every per-page skeleton in §2/§3 composes from these rather than each page hand-rolling
  its own `animate-pulse` divs, which is what happened organically in `CategoryCreateEditPanel`
  (`IconGridSkeleton`, `ColorRowSkeleton`) and would otherwise happen again on every page below.
  **Restraint rule**: skeletons represent meaningful structure (a row, a card, a chart) — a single
  isolated number (a stat tile mid-refresh, `MetricTile` showing `'—'`) does not automatically need
  an animated block; `'—'` or a small inline "Refreshing…" is often the better fit, and stays that
  way unless the surrounding content actually has real shape to hint at. **Prohibited**: skeleton
  rows inside a virtualized list. Nothing in this proposal is virtualized today, but a skeleton row
  repeated across a 500-row virtualized table is expensive for little benefit — if virtualization
  shows up later, it gets a different (or no) loading treatment, not this pattern.
- **`useDelayedLoading(isLoading, { showAfter, minVisible })` hook**, consumed by every page's
  skeleton branch instead of gating on the raw `isLoading` flag directly. Two timing rules, both
  handled once here rather than reimplemented per page:
  - **Delay before showing**: a fetch that resolves in ~100-200ms shouldn't show a skeleton at all —
    flashing one in and out reads as noise, not as "fast." Default `showAfter: 200ms`.
  - **Minimum visible time**: once a skeleton *does* show, it shouldn't vanish 20ms later either
    (a different kind of flicker). Default `minVisible: 300ms`.
  - **Initial load only — never background refetches.** `useDelayedLoading` takes a query's
    `isLoading` (first fetch, no data yet), not `isFetching`/`isRefetching` (data already present,
    quietly updating). Feeding it `isFetching` would replace visible content with a skeleton on
    every background refresh — the opposite of what a background refetch should do. A refetch with
    existing data stays on the "stale content + spinner" row of the UX convention table below
    (Ledger's existing "Refreshing…" pattern), never the skeleton row.
- **Accessibility: `Skeleton.Region` provides `aria-busy="true"`, `role="status"`,
  `aria-live="polite"` — required, not automatic.** Individual shapes (`Row`/`Card`/`Chart`/...)
  are `aria-hidden` on themselves, since a list of skeleton rows must announce as one region, not
  one per row — which means the accessibility contract only exists where a page actually wraps its
  shapes in one `Region`. A page that swaps `<p>Loading…</p>` for a bare `Skeleton.Row` and forgets
  the `Region` wrapper is *worse* than before (readable text replaced by something that announces
  nothing), not better. Every per-page skeleton composition in §2/§3/§4 wraps its shapes in exactly
  one `Region` per logical loading area — call this out explicitly in each phase's PR description
  and review, don't assume it's automatic.
- **Performance guardrail**: animate only `transform`/`opacity` (what `whileTap`/`whileHover` and the
  skeleton shimmer already do) — never `width`/`height`/`top`/`left`/`margin`/`padding`, which force
  layout and janks on slower devices. Stated explicitly here so it's a documented rule new
  contributors can check against, not just an accident of what happened to get built first.
- **Framer Motion scope — explicit allow-list**, since a dependency's usage tends to spread
  organically once it's available:
  - **Allowed**: `<Button>`/`<IconButton>` tap+hover, skeleton-to-content transitions, drawer/panel
    open-close (`EntityDrawer`, review panels), the Import wizard's step transition (§3, kept as its
    own PR — see below). **Skeleton-to-content transitions are opacity-only**, same `duration-200`
    convention as everything else — no slide, scale, bounce, stagger, or delayed reveal. The loading
    state disappears quietly; it doesn't get its own choreography.
  - **Not allowed**: `DataTable`/list rows (any of them, in-scope pages included — motion on a row a
    user is scanning quickly works against the "don't slow down operator scanning" reasoning that
    already scoped out the 3 excluded pages), decorative/arbitrary hover effects with no functional
    signal, anything on the landing pages (out of scope entirely, separate system).
- **Tailwind config**: neither app's `tailwind.config.js` has any custom `transitionDuration`,
  `transitionTimingFunction`, `animation`, or `keyframes` — everything today is Tailwind's stock
  defaults. Add one shared duration/easing convention (`duration-200 ease-out` for hover/tap,
  matching what `Button` uses) so it's a named choice, not an accident of whatever each page
  happened to type.
- **`admin-portal`: add `framer-motion` as a new dependency** (`^13.1.1`, matching `frontend/`'s
  pinned version). This is the one infra change in this proposal — flagging it up front rather than
  bundling it invisibly into "Phase 4."
- **Respect `prefers-reduced-motion`.** admin-portal already does this correctly in exactly one
  place (`.animate-heartbeat`, `index.css`) — the only place in either app that does. `Button`'s
  `whileHover`/`whileTap` and any new skeleton shimmer should carry the same guard
  (`useReducedMotion()` from framer-motion, or a CSS `@media` fallback for the Tailwind-only skeleton
  pulses).
- **Section-scoped loading, not page-scoped, whenever sections are independently sourced.** This is
  the fix Dashboard needed (§3), stated as a general rule rather than a Dashboard-specific one: when
  a page's sections are backed by independent queries with no functional dependency between them,
  each section gates on its own loading state. A page-level AND-of-everything gate is only correct
  when sections genuinely can't render without each other's data — which is the exception, not the
  default, in every page surveyed for this proposal.
- **Leave `PageLoading.tsx` alone.** Its "Loading…" text instead of a spinner is a documented,
  deliberate choice (a route chunk usually loads in under a frame; a spinner would flash and read as
  jank). This proposal's skeletons are for *data* loading (which can genuinely take a visible amount
  of time), not route-chunk loading — don't conflate the two or override a decision that was made on
  purpose.

**Not building:** a new spinner component. The existing `Loader2`/`RefreshCw` +
`animate-spin` icon pattern already works and is consistent everywhere it's used — `Button`'s
`loading` prop just adopts it rather than inventing a replacement.

**UX convention** every page's loading branch should follow once `<Skeleton>`/`useDelayedLoading`
exist (a shared convention, not a mandated rewrite of each page's data-fetching into a literal state
machine — see "Explicitly deferred" below for why that's a separate, bigger change):

| State | UI |
|---|---|
| initial load | skeleton (after the delay window) |
| refetch/background update | stale content stays visible + small spinner (Ledger's existing "Refreshing…" pattern, extended with an icon) |
| empty (loaded, genuinely nothing there) | `EmptyState` |
| error | existing error text + a retry action, where a retry is meaningful (mirrors `CategoryCreateEditPanel`'s options-fetch retry, shipped in PR #682) |
| success | real content |

## §2. Fix the three flash-of-wrong-content bugs (bundled with Phase 1, `frontend/`)

Once `<Skeleton>` exists, each of these gets: an actual `loading` state var (they have none today),
a skeleton shown while `loading` is true, real content after.

| Page | File | Current bug |
|---|---|---|
| Budgets | `frontend/src/pages/Budgets.tsx` | `budgets` starts `[]`, `EmptyState` renders before the fetch resolves, every load |
| Goals | `frontend/src/pages/Goals.tsx` | Same pattern, `goals` starts `[]` |
| Accounts/Setup | `frontend/src/pages/Setup.tsx` | Same pattern — worst instance visually, since real account cards (bank logo, balance, masked number) are richer than the other two |

## §3. `frontend/` — page-by-page (highest-traffic first)

| Priority | Page | What changes |
|---|---|---|
| 1 | **Dashboard** (`Dashboard.tsx`) | Today's gate is `summaryQ.isLoading \|\| accountsQ.isLoading \|\| recentTxnsQ.isLoading \|\| goalsQ.isLoading` — four independent queries ANDed into one all-or-nothing block. Split it: KPI grid, health score, recent-transactions list, and goals each show their own `Skeleton.Card`/`Skeleton.Row` gated on their *own* query's loading state, so whichever section's data arrives first renders first instead of everything waiting on the slowest one. Pass a real `loading` prop into `ChartContainer` so the Cash Flow / category charts render `Skeleton.Chart` instead of centered text. All buttons (`Import Statement`, `View Reports`, the floating `+` action button) become `<Button>`/`<IconButton>` — the `+` button and Quick Action tiles are the first `hoverScale` adopters (§1). |
| 2 | **Ledger/Transactions** (`Ledger.tsx`) | This page is half-migrated already (`AskOnceCard`/`MerchantGroupReviewCard` at the top already have the treatment). Finish it: table body's `Loading…` text row → `Skeleton.Row` ×N matching real row height; row action buttons (edit/delete) and pagination buttons → `<IconButton>`; the "Refreshing…" background-fetch indicator gets a small spinner icon, not just text. |
| 3 | **Settings** (`Settings.tsx`, 796 lines, busiest page for this concern — moved ahead of Import: it's more inconsistent today, where Import already has a decent loading UX) | 4 independently-loading sections (General/Security/Data outer gate, AI, Active Sessions, Connected Apps/Gmail) all currently show plain "Loading…" text and none can render until the outer gate clears even though only General actually needs to. Give each section its own skeleton so they can appear independently. `MetricTile`s showing `'—'` while `importStats` is null stay `'—'` (§1's "don't skeletonize tiny content" rule — a single stat number isn't structure to skeletonize). Keep the one already-good pattern (`RefreshCw`/`animate-spin` on "Sync now") as the model, extend it to `Save`/`Change Password`/`Delete Account` etc. via `<Button loading>`. |
| 4a | **Import — loading states** (`Import.tsx`) | Low-risk half, its own PR: skeleton rows for `DuplicateReview`/`VerificationPanel` while their data is in flight (same row-skeleton treatment as Ledger); all action buttons → `<Button>`/`<IconButton>`. Leaves the existing determinate progress bar and `ImportTimeline`'s per-stage spinner untouched — already the most sophisticated loading UX in the app. |
| 4b | **Import — step transitions** (`Import.tsx`) | Separate PR, after 4a ships and is verified stable: animated transition between wizard steps (`upload` → `review` → `summary`) via `AnimatePresence` fade/slide, replacing today's hard cut. Split out on purpose — this touches navigation/step-state, not just rendering, on the single largest and most complex page in the app (1982 lines), so it gets its own review and its own rollback boundary rather than riding along with the lower-risk loading-state change. |
| 5 | **Investments** (`Investments.tsx`) | Blank full-page gate → skeleton KPI cards + `Skeleton.Chart` ×2 (pass `loading` into both `ChartContainer`s, which support it but aren't given it today). Buttons → `<Button>`. |
| 6 | **Reports** (`Reports.tsx`) | Initial-load gate gets a skeleton; the bigger gap is that switching the month dropdown has *zero* loading indicator today (old data just sits there) — add the same "Refreshing…" treatment Ledger already has. Export/Print buttons → `<Button>`. |
| 7 | **Insights** (`Insights.tsx`) | Read-only page, no buttons to animate — just the loading-gate → skeleton swap. |
| 8 | **Budgets / Goals / Accounts-Setup** | Already covered in §2 (bug fix); this is where the matching `<Button>`/hover polish on their action buttons happens. |

## §4. `admin-portal` — page-by-page (in-scope pages only)

Per your instruction, the three dense-table pages keep their `DataTable` bodies exactly as they
are — no skeleton rows, no motion on individual table rows, anywhere in admin-portal, not just the
three named pages (every `DataTable`-driven page below gets its *chrome* — filters, buttons,
pagination — polished, but table bodies stay plain text/`Loading…` on every list page, matching the
"dense operator table, don't slow down scanning" reasoning).

| Priority | Page | What changes |
|---|---|---|
| 1 | **Dashboard** (`Dashboard.tsx`) | `StatCard`s showing raw `'…'` while loading → `Skeleton.Card`; provider-list "Loading…" text → skeleton rows; refresh button → `<Button loading>`. Quick Actions links get the hover treatment. Existing `animate-heartbeat` pulse-line stays untouched (it's already good, and already reduced-motion-aware — use it as the reference for how new motion here should behave). |
| 2 | **Roles & Permissions** (`Roles.tsx`) | No table at all — a card grid. Full-page `Loading…` text gate → skeleton grid of role-card placeholders. Every button (edit/delete icons, Save, revoke chips, Grant, New role/New permission) → `<Button>`. |
| 3 | **System Health** (`SystemHealth.tsx`) | Full-page gate → skeleton for the status hero + component-status grid (small fixed-shape cards, good `Skeleton.Card` fit). Refresh button → `<Button loading>`. |
| 4 | **Settings** (`Settings.tsx`) | `MfaSection` is already the best loading UX in admin-portal (real skeleton QR placeholder, inline spinners on Enroll/Confirm/Disable) — keep it as the reference pattern, don't rebuild it. Extend the same quality bar to the platform-config card and feature-flags list (currently plain "Loading…" text each). Toggle switches already animate their knob via CSS `transition-transform` — fine as-is, no need to convert to framer-motion. |
| 5 | **Merchant Review** (`MerchantReview.tsx`) | Queue table body stays plain (dense operator list, same reasoning as the excluded pages). The `ReviewPanel` decision drawer — its Approve/Rename/Discard buttons, the "Loading candidates…" text — gets full treatment: `<Button>`, skeleton for the merge-candidates list. Also flagging separately (not part of this proposal): this page uses `text-accent` where the rest of admin-portal uses `text-primary` — a pre-existing color-token inconsistency, worth a follow-up but out of scope here. |
| 6 | **Everything else in scope** (Banks, Merchant Intelligence, Merchant Templates, Global Rules, Learning Engine, Reconciliation Monitor/Explorer, Insight Explorer, Platform Analytics, Subscriptions, Referrals, Integrations, Platform Diagnostics, Layout Intelligence, Layout Studio, Import Trace, Import Row Trace, User Detail) | Lowest priority, bundled together because the change is the same mechanical swap on every one: `FilterBar` inputs, `Pagination` prev/next, and every action button (Refresh, New, Test, Retry, Save, Cancel) → `<Button>`/hover polish. `StatCard`-based pages (Learning Engine, Reconciliation Monitor) get skeleton cards instead of `'…'` text. `DataTable` bodies stay plain everywhere, per the operator-table reasoning. `UserDetail.tsx`'s 9 section files each get their own independent skeleton instead of blocking on one outer gate — same fix as `frontend/`'s Settings page (§3 priority 4). |

**Explicitly staying plain (both apps, everywhere):** every `DataTable`/dense-list table body;
`Users.tsx`, `AuditLog.tsx`, `LearningQueue.tsx` in full (their non-table chrome — `FilterBar`,
"New user", "Retry all failed", `Pagination` — still gets the `<Button>` treatment per your
"exclude the tables, not the whole page" instruction, but the row-scanning surface itself doesn't
move).

## Sequencing

This is a lot of surface area — proposing it ship as a series of separate PRs, not one giant diff:

1. **Foundation** (§1): `<Button>` + `<IconButton>` + `<Skeleton>` + `useDelayedLoading` in
   `frontend/`, same in `admin-portal` (plus the `framer-motion` dependency add), Tailwind
   duration/easing tokens. One PR, unblocks everything else.
2. **`frontend/` Phase 1** (§2): the three bug fixes, bundled — small, fast, real user-facing fix.
3. **`frontend/` Phase 2**: Dashboard + Ledger (highest traffic).
4. **`frontend/` Phase 3**: Settings (moved ahead of Import — it's the more inconsistent page today).
5. **`frontend/` Phase 4a**: Import loading states (low risk).
6. **`frontend/` Phase 4b**: Import step transitions (separate PR, after 4a is verified stable —
   see §3).
7. **`frontend/` Phase 5**: Investments, Reports, Insights (remaining pages).
8. **`admin-portal` Phase 1**: Dashboard + Roles + System Health.
9. **`admin-portal` Phase 2**: Settings + Merchant Review.
10. **`admin-portal` Phase 3**: everything else in §4's last row.
11. **Cleanup sweep**: after every phase above ships, grep both apps for anything still matching the
    old idioms — both text patterns (`Loading…`, `Loading {reference}…`, bare `'…'`/`'—'` placeholder
    values) and code patterns that often hide a bespoke implementation nothing above touched
    (`if (loading) return`, `isLoading &&`, `animate-pulse`, `Loader2`/`animate-spin` not routed
    through `<Button loading>`/`<IconButton loading>`). Every hit either gets migrated to
    `<Skeleton>`/the shared primitives, or is left as-is **only** if it maps to one of a fixed set of
    documented exception categories (route-chunk loading, per `PageLoading.tsx`'s own doc comment;
    tiny-value placeholders, per §1's restraint rule; virtualized-list rows, per §1's prohibition) —
    not a free-form "// intentional" comment, which would just become a new catch-all escape hatch
    six months from now. A hit that doesn't fit one of those three categories gets migrated, full
    stop. The goal is finishing with zero un-triaged stragglers, not zero remaining plain-text
    loading states — only these three categories are staying on purpose.

Each phase gets its own worktree/branch/PR per the project's usual workflow, verified in a real
browser session (like PR #682's categorization work) before merge — not just unit tests.

## Non-goals

**This proposal does not attempt visual redesign.** Existing page layouts, information architecture,
copy, spacing, and component hierarchy stay unchanged except where a loading state or shared
primitive genuinely requires touching them (a skeleton needs to match its real content's shape; a
`<Button>` swap needs to preserve its page's existing variant/size). Animation/polish work reliably
attracts drive-by scope ("while you're in Settings, can we also redesign this card?") — writing the
boundary down here is what makes it easy to decline that scope-creep by pointing at the doc, not by
re-litigating it PR by PR.

## Explicitly deferred, not part of this proposal

Two things from the review feedback that are genuinely good ideas but are their own separate body of
work, not a visual/component pass:

- **A formal `LoadState` state-machine refactor** of every page's data-fetching (`idle` /
  `loading` / `success` / `empty` / `error` / `refreshing` as an actual discriminated union type,
  everywhere). What's in §1 instead is the *UX convention table* — which visual treatment each
  situation gets — without mandating every page's `useState`/TanStack-Query fetch logic get rewritten
  into a literal state machine. Many pages already get most of this for free from TanStack Query's
  own `isLoading`/`isFetching`/`isPending`; others are still plain `useState`/`useEffect`. Unifying
  *that* is a real, separate, larger change than "add skeletons and animated buttons."
- **A full error-state audit** across every in-scope page (today's `if (error) return <p>...</p>`
  patterns, whether they offer retry, whether failures are silently swallowed). Worth doing — roughly
  the same size undertaking as the loading-state survey that grounded this proposal — but it's its
  own proposal with its own research pass, not something to silently fold into an animation/loading
  roadmap. Flagging it here so it doesn't get lost, not doing it as part of this.

## Success criteria

Each phase is done when, for the page(s) it covers:

- No flash-of-empty-state remains (the original Budgets/Goals/Setup bug class, anywhere it could
  recur).
- No page-level loading gate blocks a section that has its own independent data source — every
  section with its own query/fetch shows its own skeleton on its own schedule.
- No new motion ignores `prefers-reduced-motion`.
- Every `Skeleton` shape this phase renders is wrapped in a `Skeleton.Region` — no bare
  `Skeleton.Row`/`Card`/`Chart` left announcing nothing to screen readers where the page used to
  have readable "Loading…" text (§1's accessibility rule; this is easy to get wrong since the
  shapes render fine, visually, either way).
- No hand-rolled button/icon-button duplicates what `<Button>`/`<IconButton>` already provide (i.e.
  no new bare `<button className="...">` copy-pasted in a page this phase touched).
- No untriaged old-idiom string/pattern remains in that phase's pages after the cleanup sweep (§
  "Sequencing," step 11) touches them — migrated, or mapped to one of the three documented exception
  categories.
- No additional layout shift during load compared to the page's pre-migration behavior. Skeletons
  exist to reduce layout instability (real content should land in the same footprint the skeleton
  occupied); a skeleton sized wrong enough to cause its own jump when real content swaps in is a
  regression, not an improvement — check this visually in the browser verification pass, not just by
  confirming a skeleton renders at all.

That gives each PR something concrete to check against in review, beyond "looks right."
