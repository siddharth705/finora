# Finora Mobile — Correctness & Trust Roadmap

**Baselined:** 2026-09-01 · **Re-baselined:** 2026-09-03 (**Track A complete** — A4 shipped across PRs [#761](https://github.com/siddharth705/finora/pull/761), [#765](https://github.com/siddharth705/finora/pull/765), [#779](https://github.com/siddharth705/finora/pull/779))
**Owner:** Siddharth Tiwari · **Maintained by:** the PM role
**Status:** In progress — **35% overall**, Track A **100%** (A1–A4 all merged). Track B started (B1 merged, B3 in review). **All 17 remaining items re-validated 2026-09-03** — see *What re-validation changed* below before picking any of them up.
**Scope:** Mobile app hardening and trust-surface work, post-launch. This is **not** part of the
`project-plan-v1.0.md` GA gate — it is what comes after, informed by a two-pass mobile audit. See
that file for GA status; do not merge this plan's tracks into its weighted table without a deliberate
decision to do so.

## Where this came from

Two independent audit passes on the mobile app (`mobile/`), both delivered 2026-08-31/09-01:

1. **First pass** — six parallel reviewers, one per dimension (security, architecture/reliability,
   functional bugs, performance, UX/UI/accessibility, platform/native config). 35 findings.
2. **Second pass** — eight principal-level reviewers, each briefed on what pass one already found and
   instructed to go deeper rather than repeat it: architecture at 10x/100x scale, performance beyond
   memoization, adversarial security hardening, reliability under worst-day failure, financial
   correctness, import-pipeline excellence, UX/product quality, and accessibility workflow-depth, plus
   a product-vision pass benchmarking against Copilot Money/Monarch/Wealthfront/Linear/Notion/Superhuman.

Both passes are published as standalone reports (Artifacts: "Mobile Audit Ledger" and "Deep Audit
Ledger"). This document is the **action plan** distilled from them, re-prioritized after a synthesis
pass that concluded the biggest risks are not performance, security hygiene, or UI polish, but —
in order — **financial correctness, import reliability, and trust visibility** (numbers the app can't
explain or let the user correct).

## Coordination check — read this before starting any track

This repo runs concurrent sessions in shared history, with a documented record of collisions
(three separate Flyway migration-number collisions; see `CLAUDE.md`). At the time this plan was first
drafted, three related branches were still open; **rechecked 2026-09-02, two have since merged:**

> **Track A's own experience confirms this section earns its keep.** While PR #736 was in review,
> `main` took a 1,069-line refactor of `frontend/src/pages/Import.tsx` — the exact file A3's fix
> mirrors. The port survived (verified byte-identical after merging `main`, and the web gate guard
> A3 copies moved line but kept its logic), but only because it was re-checked before merge rather
> than assumed. Re-run that check, don't inherit this table's verdicts.

| Branch | Status | Relevance |
|---|---|---|
| `worktree-confirmnotduplicate-supersede-guard` | **Merged** — PR [#679](https://github.com/siddharth705/finora/pull/679), `d0295d58` on `main` | Fixed a related-but-distinct bug (`confirmNotDuplicate` refuses to un-duplicate a row whose statement was later superseded) in the same `TransactionService` neighborhood **A1/A2 will touch**. Confirmed present on `main` — no conflict, just be aware of this method's current shape before editing it. |
| `worktree-mobile-ux-bugfix` | **Merged** — PR [#660](https://github.com/siddharth705/finora/pull/660), `7ad4efc5` on `main` | Fixed the chart-memoization findings from both audit passes (`buildArcs`/`cashFlowPoints` in `DonutChart`/`CashFlowChart`/`TrendChart`), confirmed present on `main`. **Track C's chart work starts from an already-memoized baseline** — don't re-diff this. Also shipped per-query refresh-spinner gating and a skeleton screen-reader announcement (unrelated to anything tracked here). |
| `worktree-pm-status-report` | **Still open** — PR [#319](https://github.com/siddharth705/finora/pull/319), re-baselines the master GA plan to 87% | Still not merged as of 2026-09-03 (open since before this plan existed). Don't cite the 83% figure from `project-plan-v1.0.md` on `main` until this lands or is superseded — check #319 or `main` post-merge for the current number. |
| *(new 2026-09-03)* Categorization work on `main` | **Merged** — PRs [#743](https://github.com/siddharth705/finora/pull/743), [#762](https://github.com/siddharth705/finora/pull/762), [#767](https://github.com/siddharth705/finora/pull/767), [#769](https://github.com/siddharth705/finora/pull/769) | A parallel track landed structural person-to-person detection, a "Paid a Person" category (`V123`) and merchant-rail detection while A4 was in flight. **It also produced a written design spec** — `docs/superpowers/specs/2026-09-01-transaction-categorization-design.md` — which constrains anything touching categorization UI. A4 was re-scoped against it mid-flight; read it before Track C's C3. |
| *(new 2026-09-03)* Notification platform Phase A — PR [#781](https://github.com/siddharth705/finora/pull/781) | **Merged** | Added `@react-native-firebase/messaging` to `mobile/package.json`. Noted because it invalidates any locally-shared `node_modules`: a worktree symlinking another checkout's install will fail its whole suite until reinstalled. |
| `worktree-android-prod-build`, `worktree-animation-phase3-settings`, `worktree-statement-period-patterns`, `dependabot/npm_and_yarn/mobile/*` | Unrelated to this plan's scope (Android release build, animation polish, statement-period parsing, dependency bumps) | No action needed, listed for completeness. |

---

## Track weighting

Weighted by risk-adjusted priority, not task count — proposed by the PM role, adjustable by the owner.

| Track | Weight | Done | Contribution | Why this weight |
|---|---|---|---|---|
| A — Financial Correctness | 30% | **100%** ✅ | 30 | Silent wrong numbers are the single worst failure mode for a finance app; nothing here crashes or errors, so nothing catches it without a fix. **Complete** — A1/A2/A3 in PR #736, A4 across #761/#765/#779 |
| B — Import Hardening | 25% | **20%** ▲ | 5 | The app's crown-jewel flow concentrates the highest-severity findings in either audit — duplicate-import race, no idempotency, no recovery after app kill |
| C — Trust Layer | 25% | 0% | 0 | Highest strategic ROI identified across both audits: the backend/web already compute this, mobile just doesn't render it — cheap relative to its differentiation value |
| D — Security Cleanup | 20% | 0% | 0 | Real, traceable gaps (fail-open lock, indefinite unencrypted statement retention) but narrower blast radius than A/B, and mobile-only so it parallelizes cleanly |
| **Total** | **100%** | | **35%** | Track A closed 2026-09-03. **B is the recommended next track** — it holds the highest-severity findings from either audit, and its B2+B3 pair is sequenced together for a reason (see that track's own note) |

**Sequencing note:** Tracks A and B both land in `ImportService.java` / `ImportScreen.tsx` and share the
import confirm/duplicate/supersede subsystem — sequence them one at a time within a single track owner,
not as independent parallel worktrees, given this repo's collision history. Tracks C and D are
genuinely parallelizable against A/B and against each other (C is mostly new UI reading data that
already exists; D is mobile-only and touches different files than A/B).

---

## Track A — Financial Correctness

| # | Item | Severity | Files | Status |
|---|---|---|---|---|
| # | Item | Severity | Files | Status |
|---|---|---|---|---|
| A1 | ABSOLUTE-mode balance overwrite checks only other statements for recency, never live transactions dated after the statement period | High | `backend/.../imports/ImportService.java`, `repository/TransactionRepository.java` | ✅ **Merged** — PR [#736](https://github.com/siddharth705/finora/pull/736) |
| A2 | Duplicate detection is exact-string match on description — no trim/case-fold, a real false-negative gap | Medium | `backend/.../repository/TransactionRepository.java`, `imports/DuplicateIndex.java`, `service/ReconciliationService.java`, new `util/DuplicateMatching.java` | ✅ **Merged** — PR [#736](https://github.com/siddharth705/finora/pull/736) |
| A3 | Import silently defaults to `existingAccounts[0]` instead of matching the already-detected masked account number | High | `mobile/src/screens/import/ImportScreen.tsx`, new `lib/accountMatch.ts`, new `lib/importGate.ts` | ✅ **Merged** — PR [#736](https://github.com/siddharth705/finora/pull/736) |
| A4 | Categorization correction loop doesn't exist on mobile — Settings promises a review queue that isn't there; a wrong category is permanently uncorrectable | Critical | `mobile/src/screens/CategoryReviewScreen.tsx` (new), `lib/reviewQueue.ts` (new), `screens/LedgerScreen.tsx`, `DashboardScreen.tsx`, `SettingsScreen.tsx`, `components/OptionPickerModal.tsx`, `api/queryClient.ts` | ✅ **Merged** — PRs [#761](https://github.com/siddharth705/finora/pull/761) + [#765](https://github.com/siddharth705/finora/pull/765) + [#779](https://github.com/siddharth705/finora/pull/779) |

### What shipped in PR #736, and what it changed about the plan

**A1** — the recency check now also asks whether the account has a live transaction dated after the
statement's own last row (`TransactionRepository.existsLiveTransactionAfterDate`). Two details worth
carrying forward: the boundary is the statement's `maxDate`, **not** its printed period end (a
transaction between the two is by construction not on the statement, so it is genuine off-ledger
activity the closing balance does not account for); and the same-day case is a **documented, accepted
gap** — `>=` would over-block, since a manual row duplicating a statement row shares its date.

**A2** — normalized in all three paths, but via a shared `com.finora.util.DuplicateMatching` helper
rather than an inline `trim().toLowerCase()`. The reason is load-bearing and easy to undo by accident:
**Java's `String.trim()` strips every character `<= U+0020`; SQL `TRIM()` strips only the space.** An
inline trim would have made the in-memory index and the SQL query disagree about tab-padded
descriptions — which the CSV path really can produce — introducing a divergence that did not exist
while both sides were exact equality. `DuplicateIndexIT` now pins that equivalence against real
Postgres with tab/newline cases. **Do not "simplify" this helper back to `trim()`.**

**A3** — the original plan said "match the detected number, else fall back". That is *not* what
shipped, and the plan was wrong: `frontend/src/lib/accountMatch.ts` already solved this exact problem
(its doc comment quotes the identical `existingAccounts[0]` defect), so it was **ported verbatim**
instead. It filters by bank first, requires four digits before trusting a masked number, matches by
suffix across inconsistent masking, and returns null on ambiguity. A hand-rolled first draft lost every
one of those and would have matched two different banks' accounts sharing last-4. The two copies must
stay in sync — any future fix belongs in both.

---

### What shipped in A4 (PRs #761, #765, #779), and what it changed about the plan

**The plan's framing of A4 was half right.** It said "port `AskOnceCard`/`MerchantGroupReviewCard`",
and that covers the review queue. It missed the sharper half: that queue only ever holds transactions
the engine *knew* it was unsure about (`needsCategoryReview`). A transaction categorized **confidently
and wrongly** never enters it — so before this, the only route to correcting one was deleting the
transaction and re-entering it by hand. **Ledger tap-to-edit is the fix users will actually hit**, and
it was not in the plan at all.

**What the read-first pass found before any code was written**, and which the plan did not know:

- The backend already ships `GET /transactions/groups/needs-review` (`TransactionGroupingService`),
  returning merchant groups **sorted by size descending**. Mobile's API layer simply never exposed it.
  So "order by transaction-volume coverage" — which the categorization design spec calls for — came
  free rather than needing to be built.
- The two halves are **disjoint server-side**: `TransactionService.needsReview` filters out everything
  already returned inside a group. Rendering one strands the other, and the total is a clean sum.
- The design spec draws a hard line the plan didn't account for: **"Other" and "needs review" are
  different concepts**, and the latter must never be a chart slice. That is why the Dashboard surface
  is a count of work with somewhere to go, not a wedge in the spending donut.

**Deliberately out of scope:** creating a *new* category from the picker. Mobile has no
category-creation UI anywhere (`categoriesApi` exposes only `list`), so adding one here would have been
a one-off inconsistent with Budgets and every other picker. A user whose correct category doesn't exist
yet still has no on-device route to it — **worth its own item if it matters.**

### The process lesson from A4, which cost two follow-up PRs

**#761 shipped CI-green, self-reviewed, and still had five real defects.** Two were caught by an
adversarial review that was cancelled partway (only 2 of 7 dimensions reported — both found merged
bugs); three more by re-running the remaining five lenses afterwards. Concretely:

- The queue lived in the TanStack cache while every successful save invalidated **those very keys**, so
  resolving row A refetched a server that hadn't committed row B yet and **B reappeared as
  uncategorized**. Fixed by hiding resolved rows in local state instead of mutating the cache — which
  also made rollback positional for free. Note this race was *opened by* #761's own fix for a different
  bug: dropping `useSingleFlight` is what allows two corrections in flight at once.
- `failed` was `isError && isError` with no check that rows remained, so one failed background refetch
  replaced a populated, still-actionable queue with an error card. `LedgerScreen` guards the identical
  case correctly **in the same PR** — the pattern was applied in one file and not the other.
- The Dashboard nudge stated a **confidently wrong count** whenever one of the two halves failed.
- Retry policy on the shared review keys was decided by **navigation order**: a query in query-core v5
  holds one options bag, `setOptions` replaces it wholesale, and a client-driven refetch reuses whatever
  the last observer left. Now set once via `setQueryDefaults`, which writes into `#defaultOptions` and
  survives that.
- `OptionPickerModal` — **pre-existing, behind seven screens** — could not scroll past 70% of screen
  height (Yoga defaults `flexShrink` to 0 where web CSS defaults it to 1), and its backdrop took initial
  VoiceOver focus, so opening the sheet announced the dismiss control and one double-tap closed it.

**Three of the tests written for these fixes passed against the broken code.** Asserting straight after
an awaited refetch races React's flush; `toHaveBeenCalledTimes` was already satisfied before the release
it followed; `toHaveTextContent` never saw the glyph it claimed to check. **Standing rule going into
Track B: verify every regression test fails against the unfixed code before trusting it** — a green new
test is not evidence until you've seen it red.

**Cost note.** The cancelled fan-out review (7 dimensions × 3 adversarial verifiers) was expensive and
was cut for that reason; the replacement — 5 focused reviewers, no verify stage, each scoped to a named
file list — cost ~405k tokens and found just as much. Prefer that shape.

---

## What re-validation changed (2026-09-03)

Three items in a row — A4, B1, B3 — turned out to have premises that were wrong or stale in the
same few ways, each discovered only while implementing. So every remaining item was re-checked
against what the code actually does now, before any more of them get built.

**Nine of seventeen were wrong as written.** None were already done, but eight are narrower than
described and one is purely a UI port. The recurring shapes:

| Shape | Items | What it means |
|---|---|---|
| **Capability exists, only the UI is missing** | C1 | The API already returns it and the mobile types already carry it, with a comment saying "web only so far". Zero consumers. |
| **Narrower than written** | B2, B4, B5, C3, C4, C7, C8, D6 | The gap is real but part of it is already solved, deliberate, or structurally impossible. |
| **Still valid as written** | C2, C5, C6, D1, D2, D3, D4, D5 | Re-checked, unchanged. D2 is if anything *broader*. |

**Corrections that change what gets built:**

- **B4's "no timeout" half is wrong and should not be fixed.** `timeout: 0` on statement upload is
  deliberate and documented: an upload on a slow mobile connection can legitimately exceed 30s, and
  unlike an ordinary JSON call it already gives the user live proof of progress via
  `onUploadProgress`. Adding a timeout would regress exactly the case it exists for. What remains
  is only the missing *cancel* affordance — which needs the same `AbortController` plumbing as B2,
  so **B2 and B4 are now one item.**
- **B5 loses ReportsScreen entirely.** Its CSV/PDF export builds the file locally from
  already-fetched data — there is no server request in that path at all, so it cannot be "the same
  race as B1", and it is already guarded by state *and* `disabled`. Only StatementHistory's
  re-import remains, and its severity drops: a double-tap there creates a duplicate *staging
  session*, not duplicate ledger rows, because the confirm downstream is now claimed server-side by
  PR [#789](https://github.com/siddharth705/finora/pull/789).
- **B2 loses its idempotency half.** Done for re-import (V133), and structurally unnecessary for
  first-time confirm, which the server already claims atomically. The original framing — "a
  mid-confirm app kill leaves the outcome unknowable" — is now false for both paths. What is left
  is request-lifecycle hygiene, not import correctness.
- **D6 loses its security framing.** `findByIdAndUserId` already prevents the deep link replaying
  against a different account. The real remaining bug is a stale `pendingRef` across an identity
  change — a correctness issue, not an account-takeover one.
- **C8 is much smaller than assumed, because a claim repeated in this codebase's comments is
  stale.** Several files assert "this repo has no background job infrastructure"; `@Scheduled`
  is in fact used in at least five services (`ImportJobWorker`, `StatementStorageSweepService`,
  `ImportSessionService`, `RateLimiter`). C8 is one per-user daily sweep over an already-idempotent
  save, whose only real design question is per-user timezone.
- **C6 now depends on C4.** Without a param-accepting Transactions route, "View in Ledger" can only
  land on an unfiltered ledger, which is not the promise. Sequence C4 first or the win is hollow.
- **C7 is an admin→user re-scope, not a build.** The derivation, joins and DTOs already exist; the
  work is a user-scoped, ownership-checked read endpoint plus a mobile screen. The existing UI is in
  the **admin portal**, so "port the web app" does not apply here the way it did for A3/A4.

**One thing re-validation did NOT find:** any item already shipped. The plan's *contents* have held
up; its *sizing and framing* have not. Re-check an item against the code before scheduling it, not
after committing to it.

---

## Track B — Import Hardening

| # | Item | Severity | Files | Status |
|---|---|---|---|---|
| B1 | Double-tapping Import during a re-import creates duplicate transactions — no `useSingleFlight` guard, backend confirm overload skips the atomic session-claim | High | `mobile/src/screens/import/ImportScreen.tsx:252-288` | ✅ **Merged** — PR [#789](https://github.com/siddharth705/finora/pull/789) |
| B2 | Import confirm has no idempotency key and no request cancellation — a mid-confirm app kill or logout leaves the outcome unknowable, seeding a real duplicate-import path | High | `mobile/src/api/client.ts`, `endpoints.ts` (zero `AbortController` usage anywhere) | **Re-scoped 09-03** — idempotency half done (#789); merge with B4 as one *cancellable requests* item |
| B3 | Import review state has zero persistence — an OS kill at any stage during review is a silent total loss with no resume prompt | High | `mobile/src/screens/import/ImportScreen.tsx` (all review state is plain `useState`), `api/queryPersistence.ts:9-17` | 🔄 **In review** — PR [#870](https://github.com/siddharth705/finora/pull/870). Was mis-scoped: server already persisted sessions, mobile API had list/get/discard with zero callers |
| B4 | Statement upload has no timeout and no cancel control | Medium | `mobile/src/api/endpoints.ts:268-283` (`timeout: 0`) | **Re-scoped 09-03** — "no timeout" is deliberate, do NOT add one; only the cancel affordance remains. Merge with B2 |
| B5 | StatementHistory's Re-import/Share and Report CSV export have the same state-only double-tap race as B1 | Medium | `mobile/src/screens/StatementHistoryScreen.tsx:84-152`, `ReportsScreen.tsx:74-85` | **Re-scoped 09-03** — ReportsScreen dropped (no server call, already guarded). StatementHistory re-import only; duplicate *staging session*, not duplicate rows |

**Fix order within the track:** B3 (persistence) and B2 (idempotency/cancellation) are the foundation —
fixing B1's `useSingleFlight` gap without also fixing B2's missing idempotency key only closes the
UI-level race, not the app-kill/logout race that produces the same duplicate-import outcome through a
different path. Do B2+B3 together, then B1, then B4/B5 (same pattern, lower urgency).

---

## Track C — Trust Layer

The single highest-leverage discovery across both audits: the backend and web app already compute and
display a tier of trust/explainability features that mobile — the primary surface — doesn't render at
all. This track is mostly **new UI reading data that already exists**, not new domain logic.

| # | Item | Files it builds on | Status |
|---|---|---|---|
| C1 | Port "Show Your Work": health-score breakdown, Detected Duplicates card, Categorization Confidence card to mobile Dashboard | `backend/.../service/DashboardService.java`, reference impl at `frontend/src/pages/Dashboard.tsx` | **UI only 09-03** — API returns it, mobile types already carry it ("web only so far"), zero consumers. Port `frontend/src/pages/Dashboard.tsx` L374-450 / L457-480 / L514-562 |
| C2 | Promote the statement coverage-gap warning from a buried Insights sentence to a proactive Dashboard banner with a CTA into Import | `backend/.../imports/StatementCoverageAnalyzer.java`, `InsightsService.CoverageCaveat` | Not started — **re-validated, still valid**. Bigger than a UI port: endpoint is per-account and admin-gated, no mobile method, no aggregate field |
| C3 | Surface `categorySource`/`ruleId` on import review rows the same way `duplicateMatch` is already surfaced (learned / user rule / global rule / file / default) | `mobile/src/types/index.ts:212-219`, `screens/import/StagedRowCard.tsx:102-104` | **Re-scoped 09-03** — data + low-confidence badge already shipped (#743). Only confident-source provenance remains |
| C4 | Universal drill-through: category/date filters on Ledger, wired from every donut legend row, budget card, insight/mover row, and report category row into a filtered Ledger view | `mobile/src/api/endpoints.ts:97-110` (`TransactionFilters` already supports this), `LedgerScreen.tsx`, `DonutChart.tsx`, `BudgetsScreen.tsx`, `InsightsScreen.tsx`, `ReportsScreen.tsx` | **Re-scoped 09-03** — no API work needed; widen `AppTabParamList.Transactions` to take optional filters + wire callers. No web precedent to port |
| C5 | "As of" staleness signal on Total Balance, matching the pattern already built for the Income/Expense/Net Savings KPIs | `mobile/src/screens/DashboardScreen.tsx:163-187` | Not started — **re-validated, still valid**. Small: fill the empty `kpiDelta` slot + the matching accessibilityLabel branch |
| C6 | "View in Ledger" button on the import summary screen | `mobile/src/screens/import/ImportScreen.tsx:383-415` | Not started — **re-validated, still valid**, but now **depends on C4** (without a param-accepting route it lands on an unfiltered ledger) |
| C7 | *(Stretch, admin→user re-scope)* Simplified, user-facing "explain this number" provenance trail | `backend/.../imports/evidence/*`, `.../imports/trace/*` — currently admin-only | **Re-scoped 09-03** — derivation/joins/DTOs exist; work is a user-scoped ownership-checked endpoint + screen. Existing UI is admin-portal, not web |
| C8 | *(Stretch)* Automatic daily net-worth snapshot job, removing the manual "Save snapshot" step | `backend/.../service/NetWorthService.java` (computation already correct) | **Re-scoped 09-03** — scheduling infra DOES exist (`@Scheduled` in 5+ services); "no background jobs" comments are stale. One per-user daily sweep; timezone is the design point |

**C4 is the single highest-ROI item in this entire plan** per both audits — it improves trust,
explainability, debugging, and engagement simultaneously, off data the API already supports.
**C6 is the easiest win in the whole plan** — a few hours, closes the loop on the app's most
differentiating action.

**What Track A/A4 changed for this track — read before starting C3 or C4:**

- **The Ledger row's tap gesture is now taken.** `onPress` opens the category picker and `onLongPress`
  still deletes, with `delete` declared as the row's only custom accessibility action. C4's drill-through
  is about navigating *into* a filtered Ledger, so it doesn't collide — but any future "tap a row to see
  its detail" needs a different affordance, and the a11y hint/actions must be revised with it rather
  than after.
- **C3 has a written spec now.** `docs/superpowers/specs/2026-09-01-transaction-categorization-design.md`
  (landed with PR #743) governs how categorization confidence may be surfaced. Two constraints bind C3
  directly: **confidence is never shown as a percentage** (use a visual tier — a dot or an
  "auto-detected" tag), and **"needs review" is a queue state, never a chart slice**. C1's
  "Categorization Confidence card" is subject to the same rules. `StagedRowCard.tsx` also moved in #743
  — re-read it rather than trusting the line numbers above.
- **A shared review surface already exists.** `CategoryReviewScreen` renders the needs-review backlog
  and the Dashboard carries a nudge into it. C1/C3 should extend those rather than introduce a second,
  competing place where categorization quality is discussed.

---

## Track D — Security Cleanup

| # | Item | Severity | Files | Status |
|---|---|---|---|---|
| D1 | App-lock fails **open**, not closed, on any SecureStore read error — no lock screen, no warning | High | `mobile/src/lib/appLock.ts:64-66`, `lib/safeStorage.ts:14-20` | Not started — **re-validated, still valid**. Scope is the enabled-flag read only; `authenticate()` already fails closed |
| D2 | Bank statements and reports persist forever, unencrypted, in the app's cache directory — no TTL, no cleanup, worse than the already-known 24h aggregate cache | High | `mobile/src/lib/statementFile.ts:28-36`, `api/endpoints.ts:376-391`, `lib/reportExport.ts:108-121` | Not started — **re-validated, BROADER**: four cache sites, not three (adds `pickStatement`'s DocumentPicker copy) |
| D3 | Screen-capture protection covers 3 of 9 sensitive screens (Ledger, Budgets, Goals, Insights, Investments, Reports missing) | High (first pass) | `usePreventScreenCapture` call sites | Not started — **re-validated, still valid**. Decide per-screen hook vs one navigator-level guard |
| D4 | Financial data (balances/transactions/budgets/reports) persisted to disk in plaintext for up to 24h | Medium (first pass) | `mobile/src/api/queryPersistence.ts:19-28` | Not started — **re-validated, still valid**. Blast radius already bounded to 8 allowlisted prefixes; logout wipe race already closed |
| D5 | Foreground-relock race lets an in-flight share sheet survive the lock screen | Medium-High | `mobile/src/components/AppLockGate.tsx:113-136` | Not started — **re-validated, still valid**. Two `Sharing.shareAsync` call sites + share-aware suppression in `AppLockGate` |
| D6 | Email-change deep link replays against a different account after sign-out, no confirmation | High (first pass) | `mobile/src/navigation/useEmailChangeDeepLink.ts:60-91` | **Re-scoped 09-03** — cross-account replay already prevented server-side (`findByIdAndUserId`). Remaining: stale `pendingRef` across an identity change |

**D1 fix:** distinguish "key absent" (open) from "read threw" (fail closed / show a retry-locked
state) in `appLock.isEnabled()` — do not let it reuse `safeStorage`'s generic null-on-error contract.

**D2 fix:** delete the cached file after `Sharing.shareAsync()` resolves (with a short grace delay),
and/or sweep the cache directory for these patterns on app start with an explicit max age.

---

## What's deliberately not in this plan

- **Performance findings** (Ledger refetch storm, unthrottled dehydrate, net-worth pagination, etc.) —
  real and documented in both audit reports, but the synthesis discussion that produced this plan
  concluded correctness/import/trust are the higher-priority risk category right now. Revisit as a
  Track E if/when A–D are substantially closed, or pull forward any individual item if it starts
  causing real user-visible pain.
- **Architecture-at-scale findings** (currency hardcoding, query-key factory, `endpoints.ts` domain
  split, typography scale) — genuine debt, cheap to fix now and expensive later, but not urgent by
  the same synthesis. Worth scheduling opportunistically alongside whichever track touches the same
  files, rather than as a dedicated track.
- **Accessibility workflow findings** (focus management in review flows, reduced-motion support,
  screen-reader-invisible form errors) — real and high-severity in the second-pass report, not folded
  into Track D because they're a distinct skill/review surface from security. Candidate for a Track E
  or folded into whichever track touches the same screens (e.g. the focus-management fix belongs
  naturally alongside Track A4's import-review rework).

## Re-baseline procedure

Update the "Done" column and this file's "Baselined" date whenever a checklist item ships (cite the
PR). Re-weight only with a deliberate owner decision, not silently. If a coordination-check branch
above merges, keep its row only while it still carries forward-looking guidance for an unstarted
track (as the memoization and design-spec rows do); otherwise remove it and note what it resolved.

**Standing rule, adopted after A4:** an item is only "Merged" once its regression tests have been
seen to **fail against the unfixed code**. Three tests written during A4 passed against the very bugs
they claimed to pin — a green new test proves nothing on its own. Cite the PR, and treat a passing
suite as necessary, not sufficient (this is the same distinction as CLOSED–VERIFIED vs CLOSED–REVIEWED
in the bug-hunt grading).
