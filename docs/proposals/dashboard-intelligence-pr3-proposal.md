# PR3 — Real Dashboard Intelligence — Scoping Proposal

**Status:** Scoping only. **Nothing here is implemented.** D-22 named PR3 as "real cash-flow
timeline from transactions, the Financial Health Score minimum-data floor from the audit above,
a 'Financial Journey' milestone section." Audited all three against current code before writing
anything, per this plan's own §8a rule — one of the three turns out to already be done.

## 1. "Real cash-flow timeline from transactions" — already done, zero scope

**Finding:** `Dashboard.tsx`'s Cash Flow Overview already renders real per-month income/expense
data (`reportsApi.forMonth(month)`, a 3/6/12-month range selector, a real `<Line>` chart) — not
the static "No data yet" placeholder the original reference screenshot showed before D-21/D-22's
own empty-state work. `git log -S"reportsApi.forMonth"` confirms this wiring predates PR3 being
named at all; D-22's own framing of this item was already stale the moment it was written, the
same "audit found it already exists" pattern this plan has hit repeatedly (D-19, D-20).

**Recommendation: drop this item from PR3 entirely.** Nothing to build.

## 2. Financial Health Score minimum-data floor — real, confirmed gap

**Finding:** `DashboardService.computeHealthScore` (`DashboardService.java:218`) has no minimum-data
guard of any kind. Traced through what a thin-data user (a handful of transactions, one calendar
month of history) actually gets:
- `consistencyScore` defaults to 100 with 1 data point (`variance` only computes when
  `monthlyExpense.size() > 1`) — this one component is accidentally *generous* with thin data, not harsh.
- `cashFlowScore` can land at 0% if that single month's expenses exceed income (plausible for
  someone who just imported one statement before any income shows up in it).
- `emergencyScore` can land near 0 if tracked liquid balance is small relative to that one month's spend.
- `savingsRateScore` follows directly from that same one month's income/expense.

Net effect: a genuinely new user with real but thin data can land under 40 ("Needs Attention") —
confirmed possible by construction, not hypothetical. This is the gap D-22's audit flagged, and it
holds up.

**What's NOT the gap:** a true zero-transaction user never sees this at all — `Dashboard.tsx`
already hides the whole Financial Health Score section while `isEmpty` (D-19's own gate). The
harsh-score problem is specifically the day-3-to-day-10 user: past zero, not yet past thin.

**Open question for the owner — where's the floor, and what shows instead?**
- **(a) Transaction-count floor** (e.g., require ≥10 transactions before computing/showing a real
  score) — simple, but a user who imported one large statement with 50 transactions in one day
  clears it instantly while someone adding transactions by hand for two weeks doesn't.
- **(b) Time-span floor** (e.g., require ≥1 full calendar month closed out) — matches what the
  score's own components already assume (month-over-month comparisons), but a user who imports
  6 months of historical statements on day one would still be gated for no real reason.
- **(c) Both, whichever clears first** — most permissive, most complex to explain in copy.

Below the floor, the ORIGINAL "Team" narrative's own mockup (`Getting Started / Complete your
profile to unlock your score / 40% setup complete`) is one option; another is simply hiding the
section the same way the zero-transaction case already does, extending D-19's existing gate rather
than adding a new progressive-completion UI. The "% setup complete" framing needs its own decision
too if chosen: percent of *what* — transaction count toward the floor, or discrete setup steps
(account added / first import / first budget)?

## 3. "Financial Journey" milestone section — genuinely new, real gap

**Finding:** No existing infrastructure anywhere — grepped for
`milestone|onboarding.*progress|financial.*journey|day.*streak|activation.*step` across the whole
codebase; the only hits are `frontend/src/pages/landing/Journey.tsx`, the **marketing landing
page's** static "how the product feels over months" narrative section — copy, not a per-user
tracked feature, and not reachable from the authenticated app at all.

**What already exists, usable as raw material:** every candidate milestone's timestamp is already
persisted, just never aggregated:
- `User.createdAt` — account creation
- `StatementImport.importedAt` — first statement imported (or `Account.createdAt` for a
  manually-added first account, if "first data source connected" should count either way)
- `Budget.createdAt` (via `BaseEntity`) — first budget created
- `Goal.createdAt` (via `BaseEntity`) — first goal created

**What's missing, confirmed by checking `Goal.java`/`GoalService.java` directly:** goal
*completion* (`currentAmount >= targetAmount`) has no completion timestamp at all — only
`updatedAt`, which any unrelated edit (renaming the goal, say) would also bump, making it an
unreliable stand-in for "the moment this goal was actually achieved." The original pitch's
"Day 30: Savings goal achieved" milestone specifically needs new tracking (a `completedAt` column
set once, the first time a contribution crosses the target) if it's in scope — the other three
milestones need only a new read-side aggregation query, no schema change.

**Open question for the owner — which milestones, and does "Day N" mean anything real?**
The original pitch's "Day 1 / Day 3 / Day 15 / Day 30" framing implies a fixed onboarding
schedule no user actually follows — the honest version is event-based ("Account created" →
"First statement imported" → "First budget created" → "First goal funded"), each showing the
*actual* elapsed days since signup once it happens, not a prescribed day number. Confirming that
reframing is intended before building it, since it changes the section from "here's your fixed
30-day plan" to "here's what you've actually done so far" — a real difference in what the feature
promises.

## What this document deliberately does not do

- Does not propose implementation for item 2's floor threshold or item 3's exact milestone list —
  both are real product decisions, not narrowed to "the obvious answer" the way most of this
  plan's other audits landed.
- Does not touch goal-completion tracking's actual migration/entity design — that's real
  implementation work for whichever answer item 3 gets, not scoping.
- Does not reopen D-19/D-20's own "surface existing intelligence" backlog (C6.3/C6.4/C6.5's
  forecasting/C6.8) — PR3 is scoped to what D-22 itself named, not a re-litigation of the wider
  C6 sequence.
