# Finora Mobile — Correctness & Trust Roadmap

**Baselined:** 2026-09-01 · **Re-baselined:** 2026-09-02 (Track A's first three items shipped — PR [#736](https://github.com/siddharth705/finora/pull/736))
**Owner:** Siddharth Tiwari · **Maintained by:** the PM role
**Status:** In progress — **22% overall**, Track A 75% (A1/A2/A3 merged, A4 not started)
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
| `worktree-pm-status-report` | **Still open** — PR [#319](https://github.com/siddharth705/finora/pull/319), re-baselines the master GA plan to 87% | Not merged as of this update. Don't cite the 83% figure from `project-plan-v1.0.md` on `main` until this lands or is superseded — check #319 or `main` post-merge for the current number. |
| `worktree-android-prod-build`, `worktree-animation-phase3-settings`, `worktree-statement-period-patterns`, `dependabot/npm_and_yarn/mobile/*` | Unrelated to this plan's scope (Android release build, animation polish, statement-period parsing, dependency bumps) | No action needed, listed for completeness. |

---

## Track weighting

Weighted by risk-adjusted priority, not task count — proposed by the PM role, adjustable by the owner.

| Track | Weight | Done | Contribution | Why this weight |
|---|---|---|---|---|
| A — Financial Correctness | 30% | **75%** ▲ | 22.5 | Silent wrong numbers are the single worst failure mode for a finance app; nothing here crashes or errors, so nothing catches it without a fix. A1/A2/A3 merged in PR #736; only A4 (the categorization-correction loop, the largest single item) remains |
| B — Import Hardening | 25% | 0% | 0 | The app's crown-jewel flow concentrates the highest-severity findings in either audit — duplicate-import race, no idempotency, no recovery after app kill |
| C — Trust Layer | 25% | 0% | 0 | Highest strategic ROI identified across both audits: the backend/web already compute this, mobile just doesn't render it — cheap relative to its differentiation value |
| D — Security Cleanup | 20% | 0% | 0 | Real, traceable gaps (fail-open lock, indefinite unencrypted statement retention) but narrower blast radius than A/B, and mobile-only so it parallelizes cleanly |
| **Total** | **100%** | | **22.5%** | Track A's three well-scoped items shipped 2026-09-02. A4 is deliberately the remaining quarter of that track because it is not a defect fix but a missing feature — see its own row below |

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
| A4 | Categorization correction loop doesn't exist on mobile — Settings promises a review queue that isn't there; a wrong category is permanently uncorrectable | Critical | `mobile/src/screens/SettingsScreen.tsx:291-355`, `api/endpoints.ts:134,138,144` (zero callers), vs. `frontend/src/components/AskOnceCard.tsx`/`MerchantGroupReviewCard.tsx` (shipped on web, never ported) | **Not started** — next up |

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

**A4 remains, and is a different kind of work.** A1–A3 were defect fixes; A4 is a missing feature —
port `AskOnceCard`/`MerchantGroupReviewCard` (or a mobile-appropriate needs-review list) into
Ledger/Dashboard, and wire a change-category affordance onto the ledger row via the existing
`OptionPickerModal`. Treat as its own sub-effort. Note the standing lesson from A3: **check what the
web app already does before designing it fresh** — `AskOnceCard` is a working implementation, not just
a reference.

---

## Track B — Import Hardening

| # | Item | Severity | Files | Status |
|---|---|---|---|---|
| B1 | Double-tapping Import during a re-import creates duplicate transactions — no `useSingleFlight` guard, backend confirm overload skips the atomic session-claim | High | `mobile/src/screens/import/ImportScreen.tsx:252-288` | Not started |
| B2 | Import confirm has no idempotency key and no request cancellation — a mid-confirm app kill or logout leaves the outcome unknowable, seeding a real duplicate-import path | High | `mobile/src/api/client.ts`, `endpoints.ts` (zero `AbortController` usage anywhere) | Not started |
| B3 | Import review state has zero persistence — an OS kill at any stage during review is a silent total loss with no resume prompt | High | `mobile/src/screens/import/ImportScreen.tsx` (all review state is plain `useState`), `api/queryPersistence.ts:9-17` | Not started |
| B4 | Statement upload has no timeout and no cancel control | Medium | `mobile/src/api/endpoints.ts:268-283` (`timeout: 0`) | Not started |
| B5 | StatementHistory's Re-import/Share and Report CSV export have the same state-only double-tap race as B1 | Medium | `mobile/src/screens/StatementHistoryScreen.tsx:84-152`, `ReportsScreen.tsx:74-85` | Not started |

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
| C1 | Port "Show Your Work": health-score breakdown, Detected Duplicates card, Categorization Confidence card to mobile Dashboard | `backend/.../service/DashboardService.java`, reference impl at `frontend/src/pages/Dashboard.tsx` | Not started |
| C2 | Promote the statement coverage-gap warning from a buried Insights sentence to a proactive Dashboard banner with a CTA into Import | `backend/.../imports/StatementCoverageAnalyzer.java`, `InsightsService.CoverageCaveat` | Not started |
| C3 | Surface `categorySource`/`ruleId` on import review rows the same way `duplicateMatch` is already surfaced (learned / user rule / global rule / file / default) | `mobile/src/types/index.ts:212-219`, `screens/import/StagedRowCard.tsx:102-104` | Not started |
| C4 | Universal drill-through: category/date filters on Ledger, wired from every donut legend row, budget card, insight/mover row, and report category row into a filtered Ledger view | `mobile/src/api/endpoints.ts:97-110` (`TransactionFilters` already supports this), `LedgerScreen.tsx`, `DonutChart.tsx`, `BudgetsScreen.tsx`, `InsightsScreen.tsx`, `ReportsScreen.tsx` | Not started |
| C5 | "As of" staleness signal on Total Balance, matching the pattern already built for the Income/Expense/Net Savings KPIs | `mobile/src/screens/DashboardScreen.tsx:163-187` | Not started |
| C6 | "View in Ledger" button on the import summary screen | `mobile/src/screens/import/ImportScreen.tsx:383-415` | Not started |
| C7 | *(Stretch, admin→user re-scope)* Simplified, user-facing "explain this number" provenance trail | `backend/.../imports/evidence/*`, `.../imports/trace/*` — currently admin-only | Not started |
| C8 | *(Stretch)* Automatic daily net-worth snapshot job, removing the manual "Save snapshot" step | `backend/.../service/NetWorthService.java` (computation already correct) | Not started |

**C4 is the single highest-ROI item in this entire plan** per both audits — it improves trust,
explainability, debugging, and engagement simultaneously, off data the API already supports.
**C6 is the easiest win in the whole plan** — a few hours, closes the loop on the app's most
differentiating action.

---

## Track D — Security Cleanup

| # | Item | Severity | Files | Status |
|---|---|---|---|---|
| D1 | App-lock fails **open**, not closed, on any SecureStore read error — no lock screen, no warning | High | `mobile/src/lib/appLock.ts:64-66`, `lib/safeStorage.ts:14-20` | Not started |
| D2 | Bank statements and reports persist forever, unencrypted, in the app's cache directory — no TTL, no cleanup, worse than the already-known 24h aggregate cache | High | `mobile/src/lib/statementFile.ts:28-36`, `api/endpoints.ts:376-391`, `lib/reportExport.ts:108-121` | Not started |
| D3 | Screen-capture protection covers 3 of 9 sensitive screens (Ledger, Budgets, Goals, Insights, Investments, Reports missing) | High (first pass) | `usePreventScreenCapture` call sites | Not started |
| D4 | Financial data (balances/transactions/budgets/reports) persisted to disk in plaintext for up to 24h | Medium (first pass) | `mobile/src/api/queryPersistence.ts:19-28` | Not started |
| D5 | Foreground-relock race lets an in-flight share sheet survive the lock screen | Medium-High | `mobile/src/components/AppLockGate.tsx:113-136` | Not started |
| D6 | Email-change deep link replays against a different account after sign-out, no confirmation | High (first pass) | `mobile/src/navigation/useEmailChangeDeepLink.ts:60-91` | Not started |

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
above merges, remove its row and note what it resolved.
