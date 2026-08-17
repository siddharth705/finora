# Finora capability audit — 2026-08-14

**Why this exists.** Premium-product planning kept proposing features Finora already ships (goals,
budgets, insights, subscription detection, merchant intelligence, a health score). This audit
establishes what actually exists, so the roadmap starts from real gaps. It is read-only: no code
was changed, and every claim below cites the file it came from.

**Bottom line, in one sentence:** *Finora's engines are strong and its product layer is thin CRUD
over them — the value is computed and then not connected, not surfaced, or not delivered.* Almost
nothing in this audit is "we haven't built the intelligence." The recurring finding is intelligence
that exists and never reaches the user.

**The single most important finding:** the self-service intelligence surfaces **existed and were
deliberately retired into the admin portal.** This is not inference — the code says so:

> "Merchant/Rule/Relationship/AuditLogEntry types used to live here, backing the self-service
> Merchants/Rules/Learning Engine/Analytics/Activity pages. **Those are admin-only now**"
> — [`frontend/src/types/index.ts:352-354`](../../frontend/src/types/index.ts)

> "The other five views this endpoint used to serve (topMerchants/trend/categoryConfidence/
> topCategories/learningGrowth) moved to `AdminUserAnalyticsController` **when the self-service
> Analytics page was retired**"
> — [`AnalyticsController.java:13-18`](../../backend/src/main/java/com/finora/controller/AnalyticsController.java)

**27 of 47 controllers are `Admin*`.** There is no `MerchantController` and no `RuleController`.
A user's own private merchant list is browsable by staff and not by the user.

---

## 1. Capability map — current state

```
Finora
├── Transaction Intelligence        ENGINE: strong    USER SURFACE: near-zero
│   ├── Categorization (5-tier cascade)        engine strong, "why" not exposed
│   ├── Merchant normalization + learning      admin-only
│   ├── Duplicate detection                    import-flow only
│   ├── Import pipeline (CSV/PDF)              strong, genuinely premium-grade
│   └── Document/evidence engine (ADR-005/6)   strong, invisible by design
├── Planning                        ENGINE: thin      USER SURFACE: basic CRUD
│   ├── Goals                                  arithmetic only
│   ├── Budgets                                sum vs static limit
│   └── Recurring detection                    REAL algorithm, one card of surface
├── Insights                        ENGINE: thin      USER SURFACE: descriptive
│   ├── Insights feed                          5 sentence templates
│   ├── Reports                                1 month, 1 dimension
│   └── Dashboard (incl. health score)         backward-looking
├── Wealth                          ENGINE: minimal   USER SURFACE: manual CRUD
│   ├── Investments                            manual entry, no prices, no returns
│   ├── Accounts / balance convention          genuinely well-engineered
│   └── Net worth                              a sum; liabilities = cards only
└── Security                        ENGINE: solid     USER SURFACE: shallow
    ├── Sessions / device revoke               real, and a premium signal
    ├── Password management                    solid
    └── Audit log                              admin-only, zero user exposure
```

## 2. Premium-readiness table

| Capability | Current state | Premium readiness | The actual gap |
|---|---|---|---|
| Import pipeline | Strong | **Ship-ready** | Already the best thing in the product |
| Sessions/devices | Real per-device revoke | **Ship-ready** | Add login history over existing `audit_logs` |
| Recurring detection | Real algorithm (interval clustering + amount tolerance) | **Surface it** | No page, no route, no sidebar entry. One card on Insights |
| Merchant intelligence | Rich engine, learning loop | **Re-expose** | Retired to admin; no user merchant view |
| Categorization "why" | 5 sources persisted | **Unblock DTO** | `TransactionDto` omits `decisionSource`/`decisionRuleId` — collapses to "Auto" |
| Audit trail | `AuditService` in ~20 services | **Re-expose** | Admin-only; user sees zero account activity |
| Ledger | Search/filter/paginate/edit | **Wire existing** | Bulk ops are dead code; account/category filters unexposed |
| Health score | Exists, hardcoded weights | **Explain it** | A number with no "why" and no next action |
| Dashboard notifications | Computed | **Render them** | `DashboardSummaryDto.notifications` is never rendered |
| Goals | Target + current + contributions | **Needs logic** | `targetDate` used in zero calculations; history written, never read |
| Budgets | Sum vs static limit | **Needs logic** | Alerts only at 100% — after it's too late |
| Insights | 5 templates, fixed thresholds | **Needs depth** | Category and merchant aggregations never join → attribution impossible |
| Reports | 1 month, 1 dimension | **Needs depth** | "PDF" is `window.print()` |
| Investments | Manual entry | **Needs a model** | No holding entity, no prices, no returns math |
| Net worth | Sum of balances | **Structurally wrong** | Loans/EMI are `NOT_MODELLED_YET` |
| Proactive delivery | — | **Absent** | Email is auth-only; no user-facing scheduled job |
| Forecasting | — | **Absent** | Zero implementations repo-wide (verified by grep) |

## 3. The four patterns behind "why doesn't it feel premium"

### Pattern 1 — Computed, then never surfaced

The most common and cheapest-to-fix defect in the product:

- `DashboardSummaryDto.notifications` — credit-card due dates and low-balance warnings are computed
  in `DashboardService.buildNotifications` and **never rendered by `Dashboard.tsx`**.
- `GoalContributionRepository.findByGoalIdOrderByContributedAtDesc` — **zero production call sites.**
  Contribution history is written and never read.
- `Transaction.tags` — editable in the Ledger modal, never rendered in the table, and no repository
  query filters on them. Write-only.
- `transactionsApi.bulkDelete` / `bulkRecategorize` — exist in the API client with **zero call
  sites**; the backend supports `MAX_BULK_IDS = 500`. There are no row checkboxes in the UI.
- `RecurringDto.nextEstimate` — a real prediction with exactly one consumer: a card on Insights.

### Pattern 2 — Intelligence retired to staff-only

Covered above. The decisive detail is `TransactionDto` omitting `decisionSource`/`decisionRuleId`:
the explanation for *why* a transaction was categorized cannot reach the UI even if the UI wanted
it. Five distinct sources (`user_rule`/`global_rule`/`learned`/`rule`/`default`) collapse into one
badge that says "Auto."

### Pattern 3 — Silos that never join

- **Budgets know nothing about Recurring.** A ₹12,000/month subscription load is invisible as a
  committed obligation; budgets treat rent and an impulse buy identically.
- **Goals read nothing.** `GoalService` injects only goal repos, `UserRepository`, `AuditService` —
  no transactions, no budgets, no income.
- **Insights' category and merchant aggregations never join.** The code knows Food is up 28% and
  separately knows the top merchant, and cannot attribute one to the other. Attribution is
  structurally impossible, not merely unimplemented.

The product knows your goal deadline, your budget limits, and your predicted future charges — and
never puts any two of those facts in the same sentence.

### Pattern 4 — Nothing is proactive

`emailProvider` has four send sites, all authentication (welcome, reset, changed, change-service).
No insight, budget, balance, or renewal ever reaches a user who hasn't opened the app. The four
`@Scheduled` jobs are all import/learning/storage plumbing. Budget alerts fire only at 100% — after
the overspend. There is no push infrastructure.

## 4. What is genuinely missing (new build, not re-exposure)

Distinguishing this from the above matters for estimating: everything in §3 is wiring; everything
here is engineering.

- **Forecasting of any kind.** Verified absent repo-wide — no cash-flow projection, no safe-to-spend,
  no month-end burn estimate. `GoalDto.targetDate` exists with no logic behind it.
- **Attribution analysis** — the join that would explain *why* a number moved.
- **A holding model** — units, cost basis, price/NAV feed, returns (no XIRR/CAGR anywhere).
- **Liability modeling** — loans/EMI are recognized at import then dropped (`NOT_MODELLED_YET`).
  Net worth is incomplete by design for any user with a loan.
- **Asset/TCO linking** — no transaction→asset construct; `tags` is the only adjacent primitive and
  it is unqueried. Greenfield.
- **Proactive delivery** — scheduled user-facing jobs, notification infrastructure.
- **AI assistant** — greenfield, and dependent on the attribution layer above to be worth anything.

## 5. Recommended sequencing

**Tier 1 — Re-expose and wire (days, not weeks).** Highest premium-perception per unit of effort,
because the backends already exist and are tested:

1. Render `DashboardSummaryDto.notifications` (already computed)
2. Expose `decisionSource`/`decisionRuleId` on `TransactionDto`; replace the "Auto" badge with the
   real reason
3. A Recurring/Subscriptions page over `RecurringService` — the algorithm is already good
4. Wire the dead bulk operations and the unexposed account/category filters in Ledger
5. A user-facing account-activity view over `audit_logs`
6. A user merchant view over what `AdminUserMerchantController` already serves

**Tier 2 — Add the missing logic to existing features (weeks).** Goal pace/projection using the
`targetDate` and history that already exist; budget mid-month burn-rate alerts instead of
100%-only; insights attribution by joining the two aggregations.

**Tier 3 — New engineering.** Forecasting, holdings/prices/returns, liabilities, proactive
delivery, assistant.

**Not in any tier until it exists: billing.** Every "premium" gate depends on entitlements that
don't exist (`FeatureFlag` fails open and must not be used as a paywall).

## 6. Caveats on this audit

- Read-only and static: this reads code, not usage. It cannot say which gaps users actually feel.
- "Premium readiness" is a judgment call, not a measurement.
- Estimates are relative sizing, not commitments.
- v1 launch blockers are out of scope here and remain the current priority — this audit is input to
  post-launch planning, per the agreed sequencing.

## 7. Corrections to earlier premium planning

Features proposed as new that **already exist**: Goals, Budgets, Insights feed, Reports,
subscription/recurring detection, merchant intelligence, financial health score
(`DashboardService.computeHealthScore`), net worth, investments (as manual entry), security
sessions/device management.

The correct framing for each is *upgrade or surface*, not *build*.
