# Finora Mobile — Correctness & Trust Roadmap

**Baselined:** 2026-09-01
**Owner:** Siddharth Tiwari · **Maintained by:** the PM role
**Status:** Not started (0% — this is the initial baseline)
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
(three separate Flyway migration-number collisions; see `CLAUDE.md`). Before starting a track below,
this baseline pass found the following **already in flight on branches that touch the same files**.
Re-check `git worktree list` / `git branch -r` for current state before assuming any of this is stale:

| Branch | Touches | Relevance |
|---|---|---|
| `worktree-confirmnotduplicate-supersede-guard` | `backend/.../transactions/TransactionService.java` (`confirmNotDuplicate`) | Adjacent to **Track A**'s duplicate-detection work — a different bug (un-duplicating a row whose statement was later superseded), same method neighborhood. Check this has landed before editing `confirmNotDuplicate`/`supersede` logic. |
| `worktree-mobile-ux-bugfix` | `mobile/src/components/charts/{DonutChart,CashFlowChart,TrendChart}.tsx`, `DashboardScreen.tsx` | Already fixes the chart-memoization findings from both audit passes (`buildArcs`/`cashFlowPoints` memoization, double-computed scaled points). **Do not re-fix this** — verify it merged, don't duplicate the diff. Also already fixes per-query refresh-spinner gating and adds a skeleton screen-reader announcement (a different a11y fix than the ones tracked below, not a duplicate). |
| `worktree-pm-status-report` | `docs/project-management/plans/project-plan-v1.0.md` | Has re-baselined the master GA plan to 87% (71 merges folded in) — **newer** than the 83% figure visible from `main` at the time of this audit. Don't cite the 83% figure; check that branch or `main` post-merge for the current number. |
| `worktree-android-prod-build`, `worktree-animation-phase3-settings`, `worktree-statement-period-patterns`, `dependabot/npm_and_yarn/mobile/*` | Unrelated to this plan's scope (Android release build, animation polish, statement-period parsing, dependency bumps) | No action needed, listed for completeness. |

---

## Track weighting

Weighted by risk-adjusted priority, not task count — proposed by the PM role, adjustable by the owner.

| Track | Weight | Done | Why this weight |
|---|---|---|---|
| A — Financial Correctness | 30% | 0% | Silent wrong numbers are the single worst failure mode for a finance app; nothing here crashes or errors, so nothing catches it without a fix |
| B — Import Hardening | 25% | 0% | The app's crown-jewel flow concentrates the highest-severity findings in either audit — duplicate-import race, no idempotency, no recovery after app kill |
| C — Trust Layer | 25% | 0% | Highest strategic ROI identified across both audits: the backend/web already compute this, mobile just doesn't render it — cheap relative to its differentiation value |
| D — Security Cleanup | 20% | 0% | Real, traceable gaps (fail-open lock, indefinite unencrypted statement retention) but narrower blast radius than A/B, and mobile-only so it parallelizes cleanly |
| **Total** | **100%** | **0%** | Initial baseline — nothing in this plan has started |

**Sequencing note:** Tracks A and B both land in `ImportService.java` / `ImportScreen.tsx` and share the
import confirm/duplicate/supersede subsystem — sequence them one at a time within a single track owner,
not as independent parallel worktrees, given this repo's collision history. Tracks C and D are
genuinely parallelizable against A/B and against each other (C is mostly new UI reading data that
already exists; D is mobile-only and touches different files than A/B).

---

## Track A — Financial Correctness

| # | Item | Severity | Files | Status |
|---|---|---|---|---|
| A1 | ABSOLUTE-mode balance overwrite checks only other statements for recency, never live transactions dated after the statement period | High | `backend/.../imports/ImportService.java:1206-1231,1540-1548` | Not started |
| A2 | Duplicate detection is exact-string match on description — no trim/case-fold, a real false-negative gap | Medium | `backend/.../repository/TransactionRepository.java:228-250`, `imports/DuplicateIndex.java:104-113` | Not started |
| A3 | Import silently defaults to `existingAccounts[0]` instead of matching the already-detected masked account number | High | `mobile/src/screens/import/ImportScreen.tsx:228-234` | Not started |
| A4 | Categorization correction loop doesn't exist on mobile — Settings promises a review queue that isn't there; a wrong category is permanently uncorrectable | Critical | `mobile/src/screens/SettingsScreen.tsx:291-355`, `api/endpoints.ts:134,138,144` (zero callers), vs. `frontend/src/components/AskOnceCard.tsx`/`MerchantGroupReviewCard.tsx` (shipped on web, never ported) | Not started |

**A1 fix:** extend the recency check to also query the account's latest live transaction date;
refuse/warn using the same pattern `ClosingBalanceGuard` already uses for uncorroborated balances.
**Do before broader Gmail-sync rollout** — the trigger condition (a transaction landing between
periodic statement imports) becomes routine, not rare, once that ships further.

**A2 fix:** normalize (trim + case-fold) description before keying/comparing. Do not loosen further
into fuzzy matching — that would trade a low false-positive rate for a worse one; the reconciliation
layer already has a separately-labeled fuzzy tier for cross-source matching where that's appropriate.

**A3 fix:** match `detected.accountNumberMasked` against existing accounts before falling back to
index 0; only default to "new account" or an explicit unresolved state when no match is found.

**A4 fix:** port `AskOnceCard`/`MerchantGroupReviewCard` (or a mobile-appropriate needs-review list)
into Ledger/Dashboard; wire a change-category affordance onto the ledger row via the existing
`OptionPickerModal`. This is the largest single item in Track A — treat as its own sub-effort.

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
