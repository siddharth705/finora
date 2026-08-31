# Financial Rules Engine — Design Proposal

**Status:** Proposal only. Design after GA blockers, production-safety work, and the current bug hunt
are closed. Same sequencing as every other document in this directory.

**Major correction to the originating draft's premise:** it proposes a new `financial_rules` table
with a generic `condition`/`action` model, as if no rule-evaluation mechanism exists. **A working
generic condition+action engine already exists** — `com.finora.rules.RuleService` +
`RuleEngineService`, operating on `category_rules` (`CategoryRule` entity), with a real `Field`
(DESCRIPTION/MERCHANT/ACCOUNT_TYPE/AMOUNT), `Operator` (CONTAINS/EQUALS/STARTS_WITH/GT/LT/BETWEEN),
and `ActionType` (ASSIGN_CATEGORY/MARK_TRANSFER/MARK_INVESTMENT/MARK_SUBSCRIPTION/ADD_TAG) model,
with USER/GLOBAL scope and priority ordering. Building a second, parallel `financial_rules` engine as
the draft proposes would duplicate this rather than extend it.

**What's genuinely separate from that engine:** today's budget/balance/due-date alerting
(`DashboardService.buildNotifications()`) is hardcoded — fixed 100%-of-limit budget check, fixed
₹2000 low-balance threshold (from `User.lowBalanceThreshold`), fixed 7-day credit-card due-date
window — computed fresh on every dashboard load, not persisted or event-driven, and **not** built on
`RuleEngineService` at all. So the real design question this proposal needs to answer isn't "build a
rules engine," it's "should spend/balance alerting be generalized onto the existing
`CategoryRule`/`RuleEngineService` machinery, or is that engine's shape (categorization actions,
transaction-scoped fields) wrong for aggregate/time-windowed conditions like 'monthly food spending >
₹10,000'?" That's a real architectural fork, not a default — recorded as an open question (§5)
rather than decided unilaterally here.

## 1. Objective

Make spend/balance/due-date alerting configurable (today: three fixed thresholds, one of which
—`lowBalanceThreshold`— is at least per-user, the other two are global constants) without building a
second rule-evaluation engine alongside the one that already exists.

## 2. What exists today (baseline — see correction above for full detail)

- `RuleEngineService`: generic field/operator/action evaluation, but scoped to per-transaction
  categorization/tagging (`matches()` operates on a single transaction's fields), not aggregate
  conditions over a time window.
- `DashboardService.buildNotifications()`: three hardcoded checks (budget ≥100% of limit, balance <
  `User.lowBalanceThreshold`, card due within 7 days), computed on every dashboard load — no
  persistence, no "rule," no user configurability beyond the one per-user balance threshold.
- `TwoFactorSmsProvider`'s transaction alert: unconditional ("every manual transaction"), not a rule
  in any sense — explicitly excluded from bulk import, deliberately simple.
- `RecurringService`: statistical pattern detection (≥3 occurrences, gap/amount consistency), not
  rule-based — but does call into `RuleEngineService.evaluateSideEffectRules` for
  `MARK_SUBSCRIPTION`, confirming `CategoryRule` is already the codebase's convention for
  "user/admin-declared condition," which weighs toward extending it rather than inventing a second
  convention.

## 3. Proposed scope (v1 — the only thing being designed here)

### 3.1 Configurable budget-warning threshold (small, no new engine)

The one piece of the draft's example that's genuinely simple and low-risk: make the budget-warning
percentage configurable instead of the current fixed 100%-of-limit check. This is a natural fit for
the `app_config` generic config value proposed in
`remote-configuration-feature-management-proposal.md` (`budget_warning_percentage`, already named as
an example in that document) — not a new table, not a rules engine, just reading a config value
instead of a hardcoded constant in `DashboardService`.

### 3.2 Per-user configurable alert thresholds (medium — extends existing per-user pattern)

`User.lowBalanceThreshold` already establishes the pattern of a per-user override. Extending the same
idea to budget-warning percentage (per-user override of the §3.1 global default) and due-date-window
days is a small, consistent extension — not a new engine, a few more nullable columns on `User` (or a
small `user_alert_preferences` table if the column count grows unwieldy).

### 3.3 The open architectural question (not designed here — see §5)

Whether "alert me when food spending exceeds ₹10,000" (an aggregate, time-windowed, user-authored
condition) belongs on `RuleEngineService` or needs its own mechanism is a real design decision with
tradeoffs on both sides, and is explicitly **not resolved by this proposal**. Building it either way
without that decision risks the exact duplication problem this proposal exists to avoid.

## 4. Explicitly out of scope

- A new generic `financial_rules` table/engine, built in parallel with `RuleEngineService` — this is
  the outcome this proposal is specifically trying to prevent.
- User-authored arbitrary condition rules (§3.3's open question) — not designed until §5 is resolved.
- Fino rule creation ("create a rule to alert me when...") — depends on §3.3 existing first; no Fino
  work here.

## 5. Open questions for whoever implements this (genuinely unresolved, not implementation detail)

- **Extend `RuleEngineService` to support aggregate/time-windowed conditions, or build a second,
  narrower mechanism purpose-built for spend alerts?** Arguments for extending: one engine, one
  mental model, reuses existing USER/GLOBAL scoping and admin tooling. Arguments against: the
  existing engine's `Field`/`Operator` model is shaped around single-transaction evaluation
  (`matches()` on one row) — aggregate conditions ("sum of category X this month") are a different
  evaluation shape entirely, and forcing them into the same abstraction might be worse than two
  simpler, purpose-fit mechanisms. This needs a design spike, not a default answer, before any of
  §3.3 is built.
- Should §3.2's per-user thresholds live on `User` directly or a separate preferences table — depends
  on how many more per-user settings accumulate over time.

## 6. Estimated effort

| Component | Effort |
|---|---|
| Configurable budget-warning threshold (global, via `app_config`) | S |
| Per-user alert threshold overrides | S–M |
| Aggregate rule-evaluation design spike (§5) | S (spike only — no build estimate until resolved) |
| User-authored spend-threshold rules (§3.3) | Not sized — blocked on §5 |
